# DAG Implementation Status - Java

**Stability:** EXPERIMENTAL (`@Experimental` on public DAG symbols).

## Extension SPI migration

- Public entry points are static `DurableDagOperation.dag(...)` and `dagAsync(...)` methods.
- `DurableContext` has no DAG-specific methods.
- The DAG container and tasks use `ExtensionContext` and opaque `ExtensionOperation` reservations.
- Task IDs use `reserve(name, "DAG_NODE_T_" + name)`; no DAG-specific operation-ID API is required.
- DAG uses string extension subtypes instead of adding values to `OperationSubType`.
- DAG adapts its pre-reserved nodes to the unchanged map, parallel, and wait-for-condition operation facades.
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
