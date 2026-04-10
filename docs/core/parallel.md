## parallel() – Concurrent Branch Execution

`parallel()` runs multiple independent branches concurrently, each in its own child context. Branches are registered via `branch()` and execute immediately (respecting `maxConcurrency`). The operation completes when all branches finish or completion criteria are met.

```java
// Basic parallel execution
var parallel = ctx.parallel("validate-and-process");
DurableFuture<Boolean> task1 = parallel.branch("validate", Boolean.class, branchCtx -> {
    return branchCtx.step("check", Boolean.class, stepCtx -> validate());
});
DurableFuture<String> task2 = parallel.branch("process", String.class, branchCtx -> {
    return branchCtx.step("work", String.class, stepCtx -> process());
});

// Wait for all branches and get the aggregate result
ParallelResult result = parallel.get();

// Access individual branch results
Boolean validated = task1.get();
String processed = task2.get();
```

`ParallelDurableFuture` implements `AutoCloseable` — calling `close()` triggers `get()` if it hasn't been called yet, ensuring all branches complete.

```java
// AutoCloseable pattern
try (var parallel = ctx.parallel("work")) {
    parallel.branch("a", String.class, branchCtx -> branchCtx.step("a1", String.class, stepCtx -> "a"));
    parallel.branch("b", String.class, branchCtx -> branchCtx.step("b1", String.class, stepCtx -> "b"));
} // close() calls get() automatically
```

### ParallelResult

`ParallelResult` is a summary of the parallel execution:

| Field | Description |
|-------|-------------|
| `size()` | Total number of registered branches |
| `succeeded()` | Number of branches that succeeded |
| `failed()` | Number of branches that failed |
| `completionStatus()` | Why the operation completed (`ALL_COMPLETED`, `MIN_SUCCESSFUL_REACHED`, `FAILURE_TOLERANCE_EXCEEDED`) |

### ParallelConfig

Configure concurrency limits and completion criteria:

```java
var config = ParallelConfig.builder()
    .maxConcurrency(5)                                    // at most 5 branches run at once
    .completionConfig(CompletionConfig.allCompleted())    // default: run all branches
    .build();

var parallel = ctx.parallel("work", config);
```

| Option | Default | Description |
|--------|---------|-------------|
| `maxConcurrency` | Unlimited | Maximum branches running simultaneously (must be ≥ 1) |
| `completionConfig` | `allCompleted()` | Controls when the operation stops starting new branches |

#### CompletionConfig

`CompletionConfig` controls when the parallel operation stops starting new branches:

| Factory Method | Behavior |
|----------------|----------|
| `allCompleted()` (default) | All branches run regardless of failures |
| `allSuccessful()` | Stop if any branch fails (zero failures tolerated) |
| `firstSuccessful()` | Stop after the first branch succeeds |
| `minSuccessful(n)` | Stop after `n` branches succeed |
| `toleratedFailureCount(n)` | Stop after more than `n` failures |

Note: `toleratedFailurePercentage` is not supported for parallel operations.

### ParallelBranchConfig

Per-branch configuration can be provided:

```java
parallel.branch("work", String.class, branchCtx -> doWork(),
    ParallelBranchConfig.builder()
        .serDes(customSerDes)
        .build());
```

### Error Handling

Branch failures are captured individually. A failed branch throws its exception when you call `get()` on its `DurableFuture`:

```java
var parallel = ctx.parallel("work");
var risky = parallel.branch("risky", String.class, branchCtx -> {
    throw new RuntimeException("failed");
});
var safe = parallel.branch("safe", String.class, branchCtx -> {
    return branchCtx.step("ok", String.class, stepCtx -> "done");
});

ParallelResult result = parallel.get();

String safeResult = safe.get();  // "done"
try {
    risky.get();  // throws
} catch (ParallelBranchFailedException e) {
    // Branch failed and the SDK could not reconstruct the original exception.
    // This happens when: the error info was not checkpointed, the exception
    // class is not on the classpath, or deserialization of the error data
    // failed. The original error type and message are in e.getMessage().
}
```

| Exception | When Thrown |
|-----------|-------------|
| `ParallelBranchFailedException` | Branch failed and the original exception could not be reconstructed |
| User's exception | Branch threw a reconstructable exception — propagated through `get()` |

### Checkpoint-and-Replay

Parallel operations are fully durable. On replay after interruption:

- Completed branches return cached results without re-execution
- Incomplete branches resume from their last checkpoint
- Branches that never started execute fresh
