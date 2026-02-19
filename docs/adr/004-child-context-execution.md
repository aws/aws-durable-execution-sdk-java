# ADR-004: Child Context Execution (`runInChildContext`)

**Status:** Accepted  
**Date:** 2026-02-16

## Context

The TypeScript and Python durable execution SDKs support child contexts via `OperationType.CONTEXT`, enabling isolated sub-workflows with independent operation counters and checkpoint logs. The Java SDK needs the same capability to support fan-out/fan-in, parallel processing branches, and hierarchical workflow composition.

```java
var futureA = ctx.runInChildContextAsync("branch-a", String.class, child -> {
    child.step("validate", Void.class, () -> validate(order));
    child.wait(Duration.ofMinutes(5));
    return child.step("charge", String.class, () -> charge(order));
});
var futureB = ctx.runInChildContextAsync("branch-b", String.class, child -> { ... });
var results = DurableFuture.allOf(futureA, futureB);
```

## Decision

### Child context as a CONTEXT operation

A child context is a `CONTEXT` operation in the checkpoint log with a three-phase lifecycle:

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

### Operation ID prefixing

Inner operation IDs are prefixed with the parent context's operation ID using `-` as separator (e.g., `"3-1"`, `"3-2"`). This matches the JavaScript SDK's `stepPrefix` convention and ensures global uniqueness — the backend validates type consistency by operation ID alone.

- Root context: `"1"`, `"2"`, `"3"`
- Child context `"1"`: `"1-1"`, `"1-2"`, `"1-3"`
- Nested child context `"1-2"`: `"1-2-1"`, `"1-2-2"`

### Per-context replay state

A global `executionMode` doesn't work for child contexts — a child may be replaying while the parent is already executing. Each `DurableContext` tracks its own replay state via an `isReplaying` field, initialized by checking `ExecutionManager.hasOperationsForContext(contextId)`.

### Thread model

Child context user code runs in a separate thread (same pattern as `StepOperation`):
- `registerActiveThread` before the executor runs (on parent thread)
- `setCurrentContext` inside the executor thread
- `deregisterActiveThread` in the finally block
- `SuspendExecutionException` caught in finally (suspension already signaled)

### Large result handling

Results < 256KB are checkpointed directly. Results ≥ 256KB trigger the `ReplayChildren` flow:
- SUCCEED checkpoint with empty payload + `ContextOptions { replayChildren: true }`
- On replay, child context re-executes; inner operations replay from cache
- No new SUCCEED checkpoint during reconstruction

### Replay behavior

| Cached status | Behavior |
|---------------|----------|
| SUCCEEDED | Return cached result |
| SUCCEEDED + `replayChildren=true` | Re-execute child to reconstruct large result |
| FAILED | Re-throw cached error |
| STARTED | Re-execute (interrupted mid-flight) |

## Alternatives Considered

### Flatten child operations into root checkpoint log
**Rejected:** Breaks operation ID uniqueness. A CONTEXT op with ID `"1"` and an inner STEP with ID `"1"` (different `parentId`) would trigger `InvalidParameterValueException` from the backend.

### Global replay state with context tracking
**Rejected:** Adds complexity to `ExecutionManager` for something that's naturally per-context. The TypeScript SDK uses per-entity replay state for the same reason.

## Consequences

**Positive:**
- Aligns with TypeScript and Python SDK implementations
- Enables fan-out/fan-in, parallel branches, hierarchical workflows
- Clean separation: each child context is self-contained
- Nested child contexts chain naturally via ID prefixing

**Negative:**
- More threads to coordinate
- Per-context replay state adds complexity vs. global mode

**Deferred:**
- Orphan detection in `CheckpointBatcher`
- `summaryGenerator` for large-result observability
- Higher-level `map`/`parallel` combinators (different `OperationSubType` values, same `CONTEXT` operation type)
