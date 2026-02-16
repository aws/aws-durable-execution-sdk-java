# Design: `runInChildContext` — Isolated Execution Contexts

## What is this?

`runInChildContext` lets developers create isolated sub-workflows inside a durable execution. Each child context gets its own operation counter and checkpoint log, enabling concurrent branches of work that each maintain independent replay state.

```java
// Sync — blocks until child completes
String result = ctx.runInChildContext("process-order", String.class, child -> {
    child.step("validate", Void.class, () -> validate(order));
    child.wait(Duration.ofMinutes(5));
    return child.step("charge", String.class, () -> charge(order));
});

// Async — run multiple child contexts concurrently
var futureA = ctx.runInChildContextAsync("branch-a", String.class, child -> { ... });
var futureB = ctx.runInChildContextAsync("branch-b", String.class, child -> { ... });
var results = DurableFuture.allOf(futureA, futureB); // preserves input order
```

Child contexts support the full range of durable operations internally: `step`, `wait`, `invoke`, `createCallback`, and nested `runInChildContext`.

## Why?

This aligns the Java SDK with the TypeScript and Python reference implementations, which already support child contexts via `OperationType.CONTEXT`. It enables patterns like fan-out/fan-in, parallel processing branches, and hierarchical workflow composition.

## How it works

### Checkpoint lifecycle

A child context is a `CONTEXT` operation in the checkpoint log. Its lifecycle:

1. **START** (fire-and-forget) — marks the child context as in-progress
2. Inner operations checkpoint with `parentId` set to the child context's ID
3. **SUCCEED** or **FAIL** (blocking) — finalizes the child context

```
Op ID | Parent ID | Type    | Action  | Payload
------|-----------|---------|---------|--------
3     | null      | CONTEXT | START   | —
1     | 3         | STEP    | START   | —
1     | 3         | STEP    | SUCCEED | "result"
3     | null      | CONTEXT | SUCCEED | "final result"
```

### Replay behavior

| Cached status | Behavior |
|---------------|----------|
| SUCCEEDED | Return cached result (no re-execution) |
| SUCCEEDED + `replayChildren=true` | Re-execute child to reconstruct large result (>256KB); inner ops replay from cache; no new SUCCEED checkpoint |
| FAILED | Re-throw cached error |
| STARTED | Re-execute (was interrupted mid-flight) |

### Operation scoping

Child contexts restart their operation counter at 1. To avoid ID collisions, `ExecutionManager` uses a composite key:

```java
record OperationKey(String parentId, String operationId) { ... }
```

This applies to the `operations` map, `openPhasers` map, and all checkpoint completion handlers. The backend's `ParentId` field on each `Operation` is the source of truth for scoping.

### Thread model

Child context user code runs in a separate thread (same pattern as `StepOperation`):
- `registerActiveThread` before the executor runs
- `setCurrentContext` inside the executor thread
- `deregisterActiveThread` in the finally block
- `SuspendExecutionException` caught and swallowed (suspension already signaled via `executionExceptionFuture`)

### Per-context replay state

The current global `executionMode` (REPLAY → EXECUTION) doesn't work for child contexts — a child may be replaying while the parent is already executing. Each `DurableContext` tracks its own replay state independently, matching the TypeScript SDK's per-entity approach.

## Key changes by file

| File | Change |
|------|--------|
| `ChildContextOperation` (new) | Extends `BaseDurableOperation<T>`. Manages child context lifecycle, thread coordination, large result handling. |
| `ChildContextFailedException` (new) | Exception for failed child contexts where the original exception can't be reconstructed. |
| `DurableContext` | New `runInChildContext`/`runInChildContextAsync` methods. New `createChildContext` factory (skips thread registration). Stores and exposes `contextId`. Per-context replay tracking. |
| `BaseDurableOperation` | New `parentId` constructor parameter. `sendOperationUpdateAsync` uses it instead of hardcoded `null`. |
| `ExecutionManager` | `OperationKey` record for composite keys. All maps (`operations`, `openPhasers`) use composite keys. `getOperationAndUpdateReplayState` accepts `parentId`. `startPhaser` takes `parentId` + `operationId`. |
| `StepOperation` | Thread ID includes context ID: `contextId:operationId-step` |
| `LocalMemoryExecutionClient` | Handles `CONTEXT` operations (was `throw UnsupportedOperationException`). Propagates `parentId` for all operation types. |

## Large result handling

Results < 256KB (measured in UTF-8 bytes) are checkpointed directly. Results ≥ 256KB trigger the `ReplayChildren` flow:
- SUCCEED checkpoint sent with empty payload + `ContextOptions { replayChildren: true }`
- On replay, child context is re-executed; inner operations replay from cache
- No new SUCCEED checkpoint is created during reconstruction

`summaryGenerator` (optional compact summary for observability) is deferred for the initial implementation.

## Orphan detection

When a parent CONTEXT completes, in-flight child operations must be prevented from checkpointing stale state. The `CheckpointBatcher` tracks completed context IDs and silently skips checkpoints from orphaned operations. This matches both the Python SDK (`_parent_done` set) and TypeScript SDK (`markAncestorFinished`).

## Error handling

`ChildContextFailedException` follows the same pattern as `StepFailedException`:
- `get()` first attempts to reconstruct and re-throw the original exception
- Falls back to `ChildContextFailedException` wrapping the `ErrorObject`

Exceptions from inner operations propagate up through the child context naturally.

## What's deferred

- `summaryGenerator` for large-result observability
- Higher-level `map`/`parallel` combinators (different `SubType` values, same `CONTEXT` operation type)
