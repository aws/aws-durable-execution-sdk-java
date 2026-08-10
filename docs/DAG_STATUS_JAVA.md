# DAG Implementation Status - Java

**Branch:** `feature/dag-support` (local only; do not push).

**Stability:** EXPERIMENTAL (`@Experimental` on public DAG symbols).

## Extension SPI migration

- Public entry points are static `DagOperations.dag(...)` and `dagAsync(...)` methods.
- `DurableContext` has no DAG-specific methods.
- The DAG container and tasks use `ExtensionContext` and opaque `ExtensionOperation` reservations.
- Task IDs use `reserve(name, "DAG_NODE_T_" + name)`; no DAG-specific operation-ID API is required.
- DAG uses string extension subtypes instead of adding values to `OperationSubType`.
- Map, parallel, and wait-for-condition expose reserved-parent overloads so extension schedulers can compose them
  without allocating a second container operation.
- `DagException` extends `DurableExecutionException` directly.
- Large results use `ExtensionContextResult.replayChildrenAboveSize`.
- DAG scheduler code does not depend on `context`, `execution`, or `primitive` implementation packages.

## Preserved behavior

- Eager registration and validation before any DAG operation launches.
- Stable task identity across replay.
- Typed task results, nested DAGs, callbacks, map, parallel, waits, invoke, and child contexts.
- Trigger rules, `runIf`, compensation, concurrency limits, and completion policies.
- Compact large-result replay while preserving aggregate counts.

## Current limitations

- `TaskExecution.startedAt` and `completedAt` are empty because the extension SPI does not expose operation timestamps.
- Parallel branches use `Consumer<ParallelDurableFuture>` and do not receive DAG dependencies.
