# DAG Implementation Status — Java

**Branch:** `feature/dag` (main untouched, nothing pushed). **Stability:** EXPERIMENTAL (`@Experimental` on all public DAG symbols).

## Tasks — all 11 DONE (each builds + tests green, committed individually)

| # | Task | Status | Verification |
|---|------|--------|--------------|
| 1 | 🚪 GATING name-based operation-ID seam (`operationIdForName` + `*AsyncWithId`) | ✅ | `OperationIdGeneratorTest` (5); zero diff to BaseDurableOperation/ExecutionManager/OperationIdentifier/replay/serde |
| 2 | `@Experimental` marker annotation | ✅ | compiles; `@Documented`, `@Retention(CLASS)`, TYPE+METHOD |
| 3 | Public enums + `Dag*Exception` hierarchy | ✅ | compiles; all 6 completion reasons, 6 trigger rules |
| 4 | `DagContext`/`TaskHandle`/`Deps`/`DagConfig`/functional ifaces | ✅ | compiles; sealed `DagCompletionConfig` (threshold-only) |
| 5 | Registration validator (`DagValidator`) + `TaskDef` | ✅ | `DagValidatorTest` (8): cycles/names/dups/foreign deps |
| 6 | Topological scheduler (`DagExecutor`) w/ explicit-ID launch | ✅ | exercised end-to-end by integration tests |
| 7 | `DagResult` impl + `resultKind`-tagged serde | ✅ | `DagResultTest` (5): round-trip + error reconstruction |
| 8 | Wire `dag()`/`dagAsync()` into `DurableContext` | ✅ | full sdk suite (991) green |
| 9 | Unit tests | ✅ | `TriggerRuleTest`(7), `TaskHandleTest`(4), + above |
| 10 | Integration + replay tests | ✅ | `DagIntegrationTest` (4): diamond, runIf skip, compensation, replay fast-path |
| 11 | Docs | ✅ | `docs/core/dag.md` |

**Build/test:** `mvn -pl sdk test` → 991 tests, BUILD SUCCESS. `mvn -pl sdk-integration-tests test -Dtest=DagIntegrationTest` → 4 tests, BUILD SUCCESS.

## Key design decisions (per spec)
- Name-based entity IDs `{parentId}-DAG_NODE_T_{name}` via the additive seam; replay determinism proven (step ran once across wait-induced suspension/replay).
- Custom result-based completion **v2-deferred** (threshold only). No `summaryGenerator` / summary envelope; large results use native child-context re-execution.
- `Deps.get(TaskHandle<T>)` typed accessor; `.reads(...)` (inline) vs `.dependsOn(...)` (ordering-only).

## v1 limitations (documented in docs/core/dag.md)
- `DagConfig.defaultRetryStrategy` accepted but not auto-injected per task yet.
- `TaskExecution.startedAt`/`completedAt` not populated (determinism: no wall-clock outside steps).
- `parallel` DAG task branches declared via `Consumer<ParallelDurableFuture>` (reuses existing type; no `ParallelBuilder`); branches don't receive `Deps`.
- Small-result direct serde rehydrates PLAIN results as generic JSON trees (erasure); precise typed reconstruction is guaranteed by the re-execution path.
- A dedicated mock-context `DagExecutorTest` was not added (scheduler is covered end-to-end by `DagIntegrationTest`).
