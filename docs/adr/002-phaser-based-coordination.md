# ADR-002: Phaser-Based Operation Coordination

**Status:** Accepted  
**Date:** 2025-12-29

## Context

Durable operations need to coordinate:
1. Background thread executing the operation
2. Main thread calling `get()` waiting for result
3. Checkpoint system persisting results before signaling completion

The result must be checkpointed BEFORE `get()` returns, otherwise a Lambda crash loses the result.

## Decision

Use Java `Phaser` for operation coordination with checkpoint-driven advancement.

```java
// Operation executes but doesn't signal completion
T result = function.get();
executionManager.sendOperationUpdate(successUpdate); // Async checkpoint
// Phaser stays in Phase 0

// ExecutionManager advances phaser AFTER checkpoint succeeds
private void onCheckpointComplete(String newToken, List<Operation> ops) {
    if (isTerminalStatus(op.status())) {
        phaser.arriveAndAwaitAdvance(); // Phase 0 → 1
        phaser.arriveAndAwaitAdvance(); // Phase 1 → 2
    }
}
```

### Two-Phase Completion Protocol

| Phase | State | Purpose |
|-------|-------|---------|
| 0 | RUNNING | Operation executing |
| 1 | COMPLETING | Waiters unblock, reactivate |
| 2 | DONE | Step thread deregisters |

**Why two phases?** Prevents race condition:
- Phase 0→1: Main thread unblocks and re-registers as active
- Phase 1→2: Step thread deregisters

Without this, step thread could deregister before main thread re-registers, causing premature suspension.

## Alternatives Considered

### CompletableFuture

```java
private final CompletableFuture<T> resultFuture = new CompletableFuture<>();

public void execute() {
    executor.execute(() -> {
        T result = function.get();
        resultFuture.complete(result); // When to complete?
    });
}
```

**Rejected because:**

1. **Checkpoint timing:** Complete before checkpoint = race condition. Complete after = CheckpointBatcher needs to know about operation futures (tight coupling).

2. **Retry handling:** CompletableFuture is single-completion. Retries need multiple attempts:
   ```
   Attempt 1 → FAIL → PENDING (retry in 30s)
   Attempt 2 → FAIL → PENDING (retry in 60s)  
   Attempt 3 → SUCCESS
   ```
   Phaser stays in Phase 0 across all attempts naturally.

3. **Thread management:** Phaser integrates cleanly with thread deregistration during `get()`:
   ```java
   public T get() {
       phaser.register();
       executionManager.deregisterActiveThread("Root"); // Allow suspension
       phaser.arriveAndAwaitAdvance();
       executionManager.registerActiveThread("Root");   // Reactivate
       phaser.arriveAndDeregister();
   }
   ```

## Consequences

**Positive:**
- Checkpoint-driven completion ensures durability
- Clean separation: ExecutionManager controls completion, not operations
- Handles retries without special cases
- Unified replay handling (same `get()` logic for new and replayed operations)

**Negative:**
- Phaser is a complex primitive with steeper learning curve
- Two-phase protocol adds cognitive overhead

## Relationship to Suspension Model

The two-phase protocol directly prevents a race condition with the suspension mechanism.

### Why Thread-Counting for Suspension?

Suspension is triggered when `activeThreads.isEmpty()`. An alternative would be explicit suspension flags (e.g., `executionManager.requestSuspension()`), but thread-counting is necessary for correctness:

**Scenario: Blocking on a retrying step**
```java
var future1 = context.stepAsync("step1", () -> failsAndRetries());
var result = future1.get(); // Root blocks here
```

With explicit flags:
- step1 sets `suspensionRequested = true`, deregisters
- Root is blocked but still registered → `activeThreads = {Root}`
- Not empty → no suspension → **deadlock**

Root must deregister when blocking to allow suspension. This brings us back to thread-counting.

**Nested blocking also works:**
```java
var future1 = context.stepAsync("step1", () -> failsAndRetries());
context.step("step2", () -> {
    return future1.get() + "-processed"; // step2-thread blocks on step1
});
```

The phaser approach supports this - step2-thread deregisters while waiting, re-registers after. (Note: current implementation hardcodes "Root" - see TODO in `StepOperation.get()`).

### The Race Condition

**Without two phases:**
```
1. Root calls get(), deregisters itself, blocks on phaser
2. Step completes, checkpoint succeeds
3. Step thread deregisters  ← activeThreads is now EMPTY → SUSPENSION TRIGGERED
4. Root unblocks, tries to re-register ← TOO LATE
```

**With two phases:**
```
1. Root calls get(), deregisters itself, blocks on phaser (Phase 0)
2. Step completes, checkpoint succeeds
3. Phaser advances to Phase 1 ← Root unblocks HERE
4. Root re-registers as active ← Back to 1 active thread
5. Phaser advances to Phase 2
6. Step thread deregisters ← Safe, Root is still active
```

The `get()` code shows this dance:
```java
executionManager.deregisterActiveThread("Root"); // Allow suspension if step retrying
phaser.arriveAndAwaitAdvance();                  // Wait for step
executionManager.registerActiveThread("Root");   // Reactivate BEFORE step deregisters
```

This allows suspension when appropriate (step is retrying, no active threads) while preventing false suspensions when the step completes normally.
