# DAG (`context.dag()`) — ⚠️ EXPERIMENTAL

> **⚠️ EXPERIMENTAL.** DAG support is an experimental feature and may be changed or removed in future releases
> **without a major-version bump**. Every public DAG type/method is annotated with
> `@software.amazon.lambda.durable.annotations.Experimental` and carries a Javadoc `@apiNote`. Do not depend on it in
> production until it is promoted to stable.

`context.dag(...)` declares and runs a **directed acyclic graph of tasks** with typed dependencies. You describe the
graph once in a declarative registration phase; the runtime schedules tasks topologically, runs independent chains
concurrently via `DurableFuture`, evaluates per-task trigger rules and `runIf` predicates, and aggregates results into
a `DagResult`. A DAG runs as a single `runInChildContext` node; each task delegates to the **same operation machinery**
as the equivalent `DurableContext` method, differing only in that its entity ID is derived from its **name**
(`{parentId}-DAG_NODE_T_{name}`) rather than the monotonic counter — the property that makes arbitrary graph shapes
replay-safe.

## Entry points

```java
DagResult dag(String name, Consumer<DagContext> register);
DagResult dag(String name, Consumer<DagContext> register, DagConfig config);
DurableFuture<DagResult> dagAsync(String name, Consumer<DagContext> register);
DurableFuture<DagResult> dagAsync(String name, Consumer<DagContext> register, DagConfig config);
```

`register` only *declares* tasks; nothing executes until it returns and the graph is validated.

## Declaring tasks and dependencies

Each `DagContext` method registers one task and returns a typed `TaskHandle<T>`. Every task function takes a `Deps`
as its first parameter (empty for roots).

```java
DagResult r = ctx.dag("etl", d -> {
    var a = d.step("a", String.class, (deps, s) -> fetchA());              // root: empty Deps
    var b = d.step("b", String.class, (deps, s) -> fetchB());
    var c = d.step("c", String.class, (deps, s) ->                          // inline deps -> typed access
                   process(deps.get(a), deps.get(b)))
             .reads(a, b);                                                   // .reads(...) = inline (typed) deps
    d.step("notify", Void.class, (deps, s) -> notifyDone())
             .dependsOn(c);                                                  // .dependsOn(...) = ordering-only
});
```

- `.reads(TaskHandle<?>...)` — **inline** deps: gate scheduling **and** are retrievable via `Deps.get(handle)`.
  Passing an undeclared handle to `Deps.get` throws `IllegalStateException`. Java cannot introspect a lambda body, so
  inline deps must be declared explicitly.
- `.dependsOn(TaskHandle<?>...)` — **ordering-only** deps: gate scheduling but are **not** retrievable via `Deps`.
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
| `ONE_SUCCESS` | ≥1 upstream SUCCEEDED             | skip           |
| `ONE_FAILED`  | ≥1 upstream FAILED                | skip           |
| `NONE_FAILED` | no upstream FAILED                | run            |

A failed task is a **terminal state, not an abort**: by default the scheduler drains the reachable graph so
compensation tasks run. When the rule is not satisfied the task is `SKIPPED` (`SkipReason.TRIGGER_RULE`) and the skip
cascades downstream. Skips checkpoint nothing.

## `runIf`

`.runIf(Predicate<Deps>)` is evaluated after the trigger rule passes; returning `false` skips the task
(`SkipReason.RUN_IF_PREDICATE`). Predicates must be synchronous and deterministic.

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
    .maxConcurrency(4)                        // >= 1; default unlimited. Limits top-level tasks only.
    .defaultTriggerRule(TriggerRule.ALL_DONE)
    .completionConfig(DagCompletionConfig.minSuccessful(3))
    .build();
```

There is **no `summaryGenerator`** (see below).

## Replay & large results (no summary envelope)

Because task IDs are name-derived (`{parentId}-DAG_NODE_T_{name}`), the scheduler can traverse in any order across
replays: each task's per-task checkpoint fast-path returns its result under its stable ID, so re-running the scheduler
reconstructs an identical `DagResult` with correct types. Small aggregates (< 256 KB) are checkpointed directly using a
`resultKind`-tagged serialization that preserves nested `MapResult`/`DagResult` instances. **Large aggregates
(≥ 256 KB) use the native child-context re-execution path** (`runInChildContext` `replayChildren`): the DAG child body
re-runs, every task hits its per-task checkpoint fast-path (no task-body re-execution), and the `DagResult` is rebuilt
in memory. There is deliberately **no JS-style `DagSummary` / `summaryGenerator` envelope** — it has no native
precedent in the Java SDK, and the re-execution path gives the same guarantee for free.

## Validation & exceptions

Validation runs once after `register` returns, before any task launches, and throws at the `dag(...)` call site:

- `DagInvalidTaskNameException` — name must match `^[a-zA-Z0-9_]+$`, be ≤ 100 chars, and not contain `DAG_NODE_T_`.
- `DagDuplicateTaskException` — duplicate task name in the same scope.
- `DagInvalidDependencyException` — dependency handle not registered in this scope.
- `DagCyclicDependencyException` — the dependency graph contains a cycle (detected via Kahn's algorithm; a diamond is
  not a cycle).

All extend `DagException` → `DurableOperationException` → `DurableExecutionException` (`RuntimeException`).

## Notes / v1 limitations

- `DagConfig.defaultRetryStrategy` is accepted but per-task auto-injection is not yet applied in v1; set retry on each
  task's config as needed.
- `TaskExecution.startedAt`/`completedAt` are not populated in v1 (wall-clock capture outside a step would be
  non-deterministic across replays).
- `parallel` branches are declared against the existing `ParallelDurableFuture` (`Consumer<ParallelDurableFuture>`);
  branches do not receive `Deps`.
