# DAG (`DagOperations.dag()`) — ⚠️ EXPERIMENTAL

> **⚠️ EXPERIMENTAL.** DAG support is an experimental feature and may be changed or removed in future releases
> **without a major-version bump**. Every public DAG type/method is annotated with
> `@software.amazon.lambda.durable.annotations.Experimental` and carries a Javadoc `@apiNote`. Do not depend on it in
> production until it is promoted to stable.

`DagOperations.dag(...)` declares and runs a **directed acyclic graph of tasks** with typed dependencies. You describe
the graph once in a declarative registration phase; the runtime schedules tasks topologically, runs independent chains
concurrently via `DurableFuture`, evaluates per-task trigger rules and `runIf` predicates, and aggregates results into
a `DagResult`.

DAG is implemented as an extension operation using the public extension SPI. The DAG container obtains the current
`ExtensionContext` and reserves one context operation. Inside that context, every task is reserved with the stable
local ID `DAG_NODE_T_{name}` through `ExtensionContext.reserve(name, localOperationId)`. The SDK namespaces and hashes
that local ID, so graph traversal order can change without changing task operation IDs. DAG does not add methods to
`DurableContext`, operation subtypes to the core enum, or implementation-only operation-ID APIs.

## Entry points

```java
import static software.amazon.lambda.durable.dag.DagOperations.dag;

DagResult dag(String name, Consumer<DagContext> register);
DagResult dag(String name, Consumer<DagContext> register, DagConfig config);
DurableFuture<DagResult> dagAsync(String name, Consumer<DagContext> register);
DurableFuture<DagResult> dagAsync(String name, Consumer<DagContext> register, DagConfig config);
```

These are static methods on `DagOperations` and must be called from a durable context thread. `register` only
*declares* tasks; nothing executes until it returns and the graph is validated.

## Declaring tasks and dependencies

Each `DagContext` method registers one task and returns a typed `TaskHandle<T>`. Every task function takes a `Deps`
as its first parameter (empty for roots).

```java
DagResult r = dag("etl", d -> {
    var a = d.step("a", String.class, (deps, s) -> fetchA());              // root: empty Deps
    var b = d.step("b", String.class, (deps, s) -> fetchB());
    var c = d.step("c", String.class, (deps, s) ->                          // inline deps -> typed access
                   process(deps.get(a), deps.get(b)))
             .reads(a, b);                                                   // .reads(...) = inline (typed) deps
    d.step("notify", Void.class, (deps, s) -> notifyDone())
             .after(c);                                                      // .after(...) = ordering-only
});
```

- `.reads(TaskHandle<?>...)` — **inline** deps: gate scheduling **and** are retrievable via `Deps.get(handle)`.
  Passing an undeclared handle to `Deps.get` throws `IllegalStateException`. Java cannot introspect a lambda body, so
  inline deps must be declared explicitly.
- `.after(TaskHandle<?>...)` — **ordering-only** deps: gate scheduling but are **not** retrievable via `Deps`.
- `deps.get(handle)` returns the upstream's declared type `T`; `deps.getOptional(handle)` returns `Optional<T>` for
  non-`ALL_SUCCESS` paths where an upstream may be FAILED/SKIPPED.

Supported task kinds: `step`, `invoke`, `callback` (submitter-based), `wait`, `waitForCondition`, `runInChildContext`,
`map`, `parallel`, and nested `dag`. Per-task configuration reuses the existing `StepConfig`/`InvokeConfig`/
`MapConfig`/`ParallelConfig`/`WaitForConditionConfig`/`WaitForCallbackConfig` types verbatim.

## Trigger rules

`.triggerRule(TriggerRule.X)` controls whether a task runs based on upstream terminal statuses (default
`ALL_SUCCESS`, or `DagConfig.defaultTriggerRule`):

| Rule          | Runs when …                       | Empty upstream |
| ------------- | --------------------------------- | -------------- |
| `ALL_SUCCESS` | every upstream SUCCEEDED          | run            |
| `ALL_FAILED`  | every upstream FAILED             | skip           |
| `ALL_DONE`    | all upstream terminal (any state) | run            |
| `ANY_SUCCESS` | ≥1 upstream SUCCEEDED             | skip           |
| `ANY_FAILED`  | ≥1 upstream FAILED                | skip           |
| `NONE_FAILED` | no upstream FAILED                | run            |

A failed task is a **terminal state, not an abort**: by default the scheduler drains the reachable graph so
compensation tasks run. When the rule is not satisfied the task is `SKIPPED` (`SkipReason.TRIGGER_RULE`) and the skip
cascades downstream. Skips checkpoint nothing.

## `runIf`

`.runIf(Predicate<Deps>)` is evaluated after the trigger rule passes; returning `false` skips the task
(`SkipReason.RUN_IF_PREDICATE`). Predicates must be **synchronous, deterministic, and pure** — they are re-evaluated on
every replay and are never checkpointed.

### A throwing `runIf` aborts the DAG (it is **not** a task failure)

Because a `runIf` predicate is pure scheduler-decision code, a predicate that **throws** is a *defect in deterministic
code*, not a business outcome — so it must not be reinterpreted as a task failure (which would fire every downstream
`ALL_FAILED` / `ANY_FAILED` / `ALL_DONE` compensation, e.g. a `NullPointerException` in a predicate issuing a refund).
Instead, a throwing `runIf` **aborts** the DAG:

- The offending task gets **no terminal state** — it is neither `FAILED` nor `SKIPPED`.
- The scheduler **starts no further tasks**; tasks that already completed keep their checkpoints.
- The DAG container checkpoints a **failure** (durable and visible in history), and the `dag(...)` operation **fails**
  with a typed **`DagPredicateException`** whose message names the offending task and whose **cause is the original
  error** (message and stack trace preserved). `DagPredicateException.taskName()` returns the offending task's name.

```java
try {
    dag("cond", d -> {
        var gate = d.step("gate", Integer.class, (deps, s) -> fetch());
        d.step("maybe", String.class, (deps, s) -> "ran")
            .reads(gate)
            .runIf(deps -> deps.get(gate) > threshold());   // if this throws, the whole DAG aborts
    });
} catch (DagPredicateException e) {
    log.error("predicate for task {} threw", e.taskName(), e.getCause());
}
```

> **Boundary note (Java-specific).** A DAG runs inside an extension context operation, so `DagPredicateException` is
> checkpointed and **reconstructed from its serialized form** before the `dag(...)` caller observes it. The
> reconstructed exception is a `DagPredicateException` that preserves its type, message, `taskName()`, and a cause
> carrying the original error's message and stack trace. As with every exception the SDK round-trips through a
> checkpoint, the **cause's concrete Java class is not preserved** (it degrades to `Throwable`); only a top-level
> exception's concrete type is recoverable. This is a general property of the SDK's exception serialization, not
> specific to `runIf`.
>
> A throwing task **body**, by contrast, is a normal task `FAILED` (see [Trigger rules](#trigger-rules)); only the
> *predicate* aborts.

## Completion (threshold only in v1)

`DagConfig.builder().completionConfig(...)` accepts one of six threshold policies:
`allCompleted`, `allSuccessful`, `firstSuccessful`, `minSuccessful(n)`, `toleratedFailureCount(n)`,
`toleratedFailurePercentage(p)`. Default (no `completionConfig`) drains the whole reachable graph. `completionReason()`
reports `ALL_COMPLETED`, `COMPLETED_WITH_FAILURES`, `MIN_SUCCESSFUL_REACHED`, or `FAILURE_TOLERANCE_EXCEEDED`.

> **v2-deferred:** Custom-predicate (result-based) completion is **not** in v1. `DagCompletionConfig` exposes only the
> threshold factories, and `DagCompletionReason.CUSTOM_COMPLETION_*` are reserved-but-unreachable.

## Results

`DagResult` provides `getResult(TaskHandle<T>) -> Optional<T>` (typed) and `getResult(String) -> Optional<Object>`
(untyped), `getStatus(...)`, grouped views (`succeeded()`/`failed()`/`skipped()`), counts, `completionReason()`, and
`throwIfError()` (throws `DagExecutionException` iff `failureCount() > 0`).

## Configuration

```java
DagConfig.builder()
    .maxConcurrency(4)                        // >= 1; default 40. Limits top-level tasks only.
    .defaultTriggerRule(TriggerRule.ALL_DONE)
    .completionConfig(DagCompletionConfig.minSuccessful(3))
    .build();
```

There is **no `summaryGenerator`** (see below).

### Default concurrency

When `maxConcurrency` is unset, the DAG scheduler runs at most **40** top-level tasks concurrently (it was previously
unbounded). An explicit `maxConcurrency` always wins, including a value above 40; the `>= 1` validation is unchanged.

The bound applies to the **DAG scheduler only** — the top-level tasks of *this* DAG, one level. It is **not** inherited
by a task's own internal fan-out:

- A `map` or `parallel` task still defaults to **unlimited** internal fan-out. A DAG task that is a 500-item map still
  fans out to 500 items internally. This divergence from `map`/`parallel` is deliberate.
- A **nested `dag`** task gets its own independent default of 40, scoped to its own top-level tasks.

Note the interaction with early completion: for a graph wider than 40 that uses `completionConfig`, capping concurrency
changes which tasks ever start, so more tasks end up **absent** (never started) rather than reaching a terminal state.
Absent tasks count only toward `totalCount`; the early-completion semantics are unchanged, but the population of
started tasks shifts.

## Replay & large results (no summary envelope)

Because task IDs use stable local reservations (`DAG_NODE_T_{name}`), the scheduler can traverse in any order across
replays: each task's checkpoint fast path returns its result under the same ID, so re-running the scheduler
reconstructs an identical `DagResult` with correct types. Small aggregates (< 256 KB) are checkpointed directly using a
`resultKind`-tagged serialization that preserves nested `MapResult`/`DagResult` instances. **Large aggregates
(≥ 256 KB) use `ExtensionContextResult.replayChildrenAboveSize`**: the DAG context body re-runs, every task hits its
checkpoint fast path (no task-body re-execution), and the `DagResult` is rebuilt in memory. The compact replay state
retains aggregate counts while task detail remains in child operations. There is deliberately **no JS-style
`DagSummary` / `summaryGenerator` envelope**.

## Validation & exceptions

Validation runs once after `register` returns, before any task launches, and throws at the `dag(...)` call site:

- `DagInvalidTaskNameException` — name must match `^[a-zA-Z0-9_]+$`, be ≤ 100 chars, and not contain `DAG_NODE_T_`.
- `DagDuplicateTaskException` — duplicate task name in the same scope.
- `DagInvalidDependencyException` — dependency handle not registered in this scope.
- `DagCyclicDependencyException` — the dependency graph contains a cycle (detected via Kahn's algorithm; a diamond is
  not a cycle).

All extend `DagException` → `DurableExecutionException` (`RuntimeException`). DAG exceptions are extension-level
errors, not failures associated with one primitive operation.

A **runtime** DAG exception — `DagPredicateException` — is thrown when a task's `runIf` predicate throws; it aborts the
DAG (see [`runIf`](#runif) above) rather than surfacing at the `dag(...)` call site during registration. It also
extends `DagException`.

## Notes / v1 limitations

- `TaskExecution.startedAt`/`completedAt` are not populated because the extension SPI does not expose operation
  timestamps.
- `parallel` branches are declared against the existing `ParallelDurableFuture` (`Consumer<ParallelDurableFuture>`);
  branches do not receive `Deps`.
