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
2. Inner operations checkpoint with `parentId` set to the child context's operation ID
3. **SUCCEED** or **FAIL** (blocking) — finalizes the child context

```
Op ID | Parent ID | Type    | Action  | Payload
------|-----------|---------|---------|--------
3     | null      | CONTEXT | START   | —
3-1   | 3         | STEP    | START   | —
3-1   | 3         | STEP    | SUCCEED | "result"
3     | null      | CONTEXT | SUCCEED | "final result"
```

Inner operation IDs are prefixed with the parent context's operation ID using `-` as separator (e.g., `"3-1"`, `"3-2"`). This matches the JavaScript SDK's `stepPrefix` convention and ensures operation IDs are globally unique — the backend validates type consistency by operation ID, so bare sequential IDs inside child contexts would collide with root-level operations.

For nested child contexts, the prefix chains naturally (e.g., `"3-2-1"` for the first operation inside a nested child context that is operation `"2"` inside parent context `"3"`).

### Replay behavior

| Cached status | Behavior |
|---------------|----------|
| SUCCEEDED | Return cached result (no re-execution) |
| SUCCEEDED + `replayChildren=true` | Re-execute child to reconstruct large result (>256KB); inner ops replay from cache; no new SUCCEED checkpoint |
| FAILED | Re-throw cached error |
| STARTED | Re-execute (was interrupted mid-flight) |

### Operation ID prefixing

To ensure global uniqueness, `DurableContext.nextOperationId()` prefixes operation IDs with the context's `parentId` when inside a child context:

- Root context: IDs are `"1"`, `"2"`, `"3"` (no prefix)
- Child context `"1"`: IDs are `"1-1"`, `"1-2"`, `"1-3"`
- Nested child context `"1-2"`: IDs are `"1-2-1"`, `"1-2-2"`

```java
private String nextOperationId() {
    var counter = String.valueOf(operationCounter.incrementAndGet());
    return parentId != null ? parentId + "-" + counter : counter;
}
```

This matches the JavaScript SDK's `_stepPrefix` mechanism. The backend validates type consistency by operation ID alone, so without prefixing, a CONTEXT operation with ID `"1"` and an inner STEP with ID `"1"` (different `parentId`) would trigger an `InvalidParameterValueException`.

`ExecutionManager` still uses plain `String` keys (the globally unique operation ID) for its internal maps, since prefixed IDs are inherently unique across all contexts.

### Thread model

Child context user code runs in a separate thread (same pattern as `StepOperation`):
- `registerActiveThread` before the executor runs
- `setCurrentContext` inside the executor thread
- `deregisterActiveThread` in the finally block
- `SuspendExecutionException` caught and swallowed (suspension already signaled via `executionExceptionFuture`)

### Per-context replay state

The current global `executionMode` (REPLAY → EXECUTION) doesn't work for child contexts — a child may be replaying while the parent is already executing. Each `DurableContext` tracks its own replay state independently via an `isReplaying` field, initialized by checking `ExecutionManager.hasOperationsForContext(parentId)`. This matches the TypeScript SDK's per-entity approach.

The `DurableContext` stores its context identity in a `parentId` field — `null` for the root context, set to the qualified context ID for child contexts. This field is passed directly to operations as their `parentId` when constructing them.

## Key changes by file

| File | Change |
|------|--------|
| `ChildContextOperation` (new) | Extends `BaseDurableOperation<T>`. Manages child context lifecycle, thread coordination, large result handling. Uses `getOperationId()` directly as `contextId` (already globally unique via prefixed IDs). |
| `ChildContextFailedException` (new) | Extends `DurableOperationException`. Wraps the `Operation` object; extracts error from `contextDetails()`. |
| `DurableContext` | New `runInChildContext`/`runInChildContextAsync` methods. New `createChildContext` factory (skips thread registration). Stores `parentId` field (null for root, contextId for child). Per-context replay tracking via `isReplaying` field. `nextOperationId()` prefixes with `parentId` for child contexts (e.g., `"1-1"`). |
| `BaseDurableOperation` | New `parentId` constructor parameter. `sendOperationUpdateAsync` uses it instead of hardcoded `null`. Protected `getParentId()` getter. |
| `ExecutionManager` | All maps (`operations`, `openPhasers`) use plain `String` keys (globally unique operation IDs). `getOperationAndUpdateReplayState` and `startPhaser` take a single `operationId` argument. New `hasOperationsForContext(parentId)` method for per-context replay initialization. |
| `StepOperation` | Thread ID uses `getOperationId() + "-step"` (operation IDs are globally unique via prefixing). |
| `LocalMemoryExecutionClient` | Handles `CONTEXT` operations (was `throw UnsupportedOperationException`). Propagates `parentId` for all operation types. |
| `HistoryEventProcessor` | Handles `CONTEXT_STARTED`, `CONTEXT_SUCCEEDED`, `CONTEXT_FAILED` events (was `throw UnsupportedOperationException`). Builds `ContextDetails` with result/error extraction. |

## Large result handling

Results < 256KB (measured in UTF-8 bytes) are checkpointed directly. Results ≥ 256KB trigger the `ReplayChildren` flow:
- SUCCEED checkpoint sent with empty payload + `ContextOptions { replayChildren: true }`
- On replay, child context is re-executed; inner operations replay from cache
- No new SUCCEED checkpoint is created during reconstruction

`summaryGenerator` (optional compact summary for observability) is deferred for the initial implementation.

## Orphan detection (deferred)

> **Status: Deferred** — orphan detection is not implemented in this release. The mechanism described below is the intended design for a future release, matching the Python and TypeScript SDKs.

When a parent CONTEXT completes, in-flight child operations must be prevented from checkpointing stale state. The `CheckpointBatcher` would track completed context IDs and silently skip checkpoints from orphaned operations. This matches both the Python SDK (`_parent_done` set) and TypeScript SDK (`markAncestorFinished`).

## Error handling

`ChildContextFailedException` follows the same pattern as `StepFailedException`:
- Extends `DurableOperationException`, wrapping the `Operation` object
- Extracts the `ErrorObject` from `operation.contextDetails().error()`
- `get()` first attempts to reconstruct and re-throw the original exception
- Falls back to `ChildContextFailedException` if reconstruction fails

Exceptions from inner operations propagate up through the child context naturally.

## What's deferred

- Orphan detection in `CheckpointBatcher` — preventing stale checkpoints from in-flight child operations after a parent CONTEXT completes (see "Orphan detection" section above)
- `summaryGenerator` for large-result observability
- Higher-level `map`/`parallel` combinators (different `SubType` values, same `CONTEXT` operation type)
