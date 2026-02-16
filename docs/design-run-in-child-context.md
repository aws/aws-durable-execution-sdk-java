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

For nested child contexts, the `parentId` uses the qualified context path (e.g., `"3:2"` for a child context created as operation `"2"` inside parent context `"3"`).

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

### Nested context ID qualification

To prevent `OperationKey` collisions when sibling contexts at different nesting levels share the same local operation ID, child contexts build a globally unique `contextId` by qualifying with the parent's context path:

- Root-level child contexts use just their operation ID (e.g., `"3"`)
- Nested child contexts include the parent path (e.g., `"3:2"` for operation `"2"` inside parent context `"3"`)

```java
var contextId = getParentId() != null ? getParentId() + ":" + getOperationId() : getOperationId();
```

This qualified `contextId` is used as the `parentId` for all operations within the child context.

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
| `ChildContextOperation` (new) | Extends `BaseDurableOperation<T>`. Manages child context lifecycle, thread coordination, large result handling. Builds qualified `contextId` for nested contexts (e.g., `"3:2"`). |
| `ChildContextFailedException` (new) | Extends `DurableOperationException`. Wraps the `Operation` object; extracts error from `contextDetails()`. |
| `DurableContext` | New `runInChildContext`/`runInChildContextAsync` methods. New `createChildContext` factory (skips thread registration). Stores `parentId` field (null for root, contextId for child). Per-context replay tracking via `isReplaying` field. |
| `BaseDurableOperation` | New `parentId` constructor parameter. `sendOperationUpdateAsync` uses it instead of hardcoded `null`. Protected `getParentId()` getter. |
| `ExecutionManager` | `OperationKey` record for composite keys. All maps (`operations`, `openPhasers`) use composite keys. `getOperationAndUpdateReplayState` accepts `parentId`. `startPhaser` takes `parentId` + `operationId`. New `hasOperationsForContext(parentId)` method for per-context replay initialization. |
| `StepOperation` | Thread ID includes parent context: `(parentId != null ? parentId + ":" : "") + operationId + "-step"` |
| `LocalMemoryExecutionClient` | Handles `CONTEXT` operations (was `throw UnsupportedOperationException`). Propagates `parentId` for all operation types. |

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
