# Migrating from 1.x to 2.x

This guide helps teams upgrade from the `1.x` line to `2.x`.

It focuses on the breaking changes introduced since `v1.2.1`, the most recent `1.x` release at the time of writing. If you are already on a newer `1.x` patch, the same migration steps still apply.

## Upgrade Checklist

- Replace `StepConfig.builder().semantics(...)` with the correct `2.x` equivalent for your intended behavior.
- Update log queries, parsers, and dashboards to use `executionArn`, `operationId`, and `operationName`.
- Rebaseline replay-sensitive logging and plugin behavior for child contexts, especially in `parallel()`, `map()`, and nested `runInChildContext(...)` workflows.
- Update any code that expected validation failures to throw `IllegalDurableOperationException`.
- Verify that custom `SerDes` implementations can deserialize SDK-managed values immediately after serialization, or explicitly opt out of the extra validation pass.

Useful searches before upgrading:

```bash
rg -n "\.semantics\(" .
rg -n "durableExecutionArn|contextId|contextName" .
rg -n "replay|isReplayingChildren|onOperationStart|onOperationEnd" sdk examples
```

## 1. Rename `StepConfig.semantics(...)` to `semanticsPerRetry(...)`

The deprecated `semantics(...)` builder method is removed in `2.x`.

This is not always a one-line rename. In `1.x`, `semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)` behaved like `2.x` `semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)` plus a `NO_RETRY` policy.

Before:

```java
var config = StepConfig.builder()
        .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
        .build();
```

Naive rename:

```java
var config = StepConfig.builder()
        .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
        .build();
```

Behavior-preserving migration for old `1.x` `AT_MOST_ONCE_PER_RETRY` usage:

```java
var config = StepConfig.builder()
        .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
        .build();
```

Migration rules:

- Old `semantics(AT_LEAST_ONCE_PER_RETRY)` maps directly to `semanticsPerRetry(AT_LEAST_ONCE_PER_RETRY)`.
- Old `semantics(AT_MOST_ONCE_PER_RETRY)` should usually become `semanticsPerRetry(AT_MOST_ONCE_PER_RETRY)` plus `retryStrategy(RetryStrategies.Presets.NO_RETRY)` if you want to preserve the old `1.x` behavior.
- If you intentionally want the corrected `2.x` per-retry semantics, use `semanticsPerRetry(AT_MOST_ONCE_PER_RETRY)` without forcing `NO_RETRY`.

What to update:

- Step configuration builders
- Shared helper methods and wrapper APIs
- Tests that asserted on `config.semantics()`

If you expose your own configuration layer on top of the SDK, rename it now so downstream users do not inherit the removed `semantics` name.

## 2. Update logger MDC field names

The main user-visible breaking change in `2.x` is the logger metadata rename so Java matches the other durable execution SDKs.

Before:

```json
{
  "durableExecutionArn": "arn:aws:lambda:...",
  "contextId": "child-context-id",
  "contextName": "inventory-check"
}
```

After:

```json
{
  "executionArn": "arn:aws:lambda:...",
  "operationId": "child-context-id",
  "operationName": "inventory-check"
}
```

What to update:

- CloudWatch Logs Insights queries
- Metric filters and alarms
- Log processors and index mappings
- Dashboards and saved searches
- Any custom JSON or MDC parsing

Important: this rename only applies to logger MDC fields. The SDK API still uses `durableExecutionArn` in places such as `DurableExecutionInput` and plugin invocation records. Do not mechanically rename every `durableExecutionArn` identifier in your codebase.

### Mixed-version rollout query

If you need one query that works during a rolling upgrade, use `coalesce(...)`:

```sql
fields coalesce(executionArn, durableExecutionArn) as executionArn,
       coalesce(operationId, contextId) as operationId,
       coalesce(operationName, contextName) as operationName
| filter executionArn = "arn:aws:lambda:..."
```

### Temporary compatibility option

If you need to preserve the old MDC keys for a short rollout window, configure `LoggerConfig` with `oldKeyNames=true`:

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
            .withLoggerConfig(new LoggerConfig(true, true))
            .build();
}
```

That can reduce migration risk while dashboards and parsers are being updated, but the recommended end state for `2.x` is the new key set.

## 3. Rebaseline replay-sensitive logging and replay APIs

`2.x` uses per-context replay state for logging and plugin callbacks instead of relying on a single global replay view.

What changes in practice:

- Replay suppression is more accurate for child contexts.
- Concurrent child contexts no longer look like fresh execution when that child is still replaying.
- Custom plugins see replay metadata that better reflects the current child context.
- `StepContext` does not expose replay state anymore.
- Step logs are attempt-based and are never replay-suppressed.

API impact:

- `isReplaying()` now belongs on `DurableContext`, not `BaseContext`.
- Code that assumed every context type had `isReplaying()` needs to be updated.
- If you were checking replay state inside step lambdas, move that logic to the surrounding `DurableContext` or redesign it around attempt-based step behavior.

What to review:

- Tests that count log lines across replays
- Dashboards that alert on replay log volume
- Custom plugins using replay-sensitive hooks or `isReplayingChildren`
- Nested workflows that use `parallel()`, `map()`, or `runInChildContext(...)`
- Any code that called `isReplaying()` on `BaseContext` or `StepContext`

The most common upgrade symptom here is not a compile error. It is changed log volume or changed replay-related assertions in tests.

## 4. Update exception handling for context validation failures

In `2.x`, invalid context usage now throws `IllegalStateException` instead of `IllegalDurableOperationException`.

This affects validation failures such as nested durable operations from unsupported thread types, for example calling a blocking durable operation from within a step execution.

What to update:

- Unit and integration tests that assert exception types
- Error classification logic
- Alerting or telemetry that treated `IllegalDurableOperationException` as an SDK defect signal
- Runbooks that distinguished user misuse from SDK or platform failures

Before:

```java
assertThrows(IllegalDurableOperationException.class, future::get);
```

After:

```java
assertThrows(IllegalStateException.class, future::get);
```

## 5. Validate serialization round trips earlier

`2.x` validates serialized results and exceptions with an immediate deserialize pass before checkpointing by default.

What changes in practice:

- Serialization problems now fail on first execution instead of surfacing later on replay.
- Operation results can be returned after a SerDes round-trip so first execution matches replay.
- Custom `SerDes` implementations must be able to deserialize SDK-managed values they serialize.
- Child-context results are validated consistently, including virtual child-context paths.

This is usually a correctness improvement, but it can surface previously hidden `SerDes` bugs during upgrade.

### New opt-out configuration

If your workload is very performance-sensitive and you need to skip the extra deserialize pass, you can opt out:

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
            .withDeserializeAfterSerialization(false)
            .build();
}
```

Use that carefully:

- Disabling this can hide serialization bugs until replay.
- First execution may return the raw result shape instead of the replay result shape.
- Custom `SerDes` implementations are still expected to be round-trip safe.

## Recommended Validation After Upgrading

1. Build and run your test suite with the `2.x` dependency.
2. Exercise one workflow that replays after `wait()`, `waitForCondition()`, or callback resume.
3. Exercise one workflow with child contexts or concurrency.
4. Verify that your log queries and dashboards still resolve the correct execution and operation identifiers.
5. Verify any code that relied on `BaseContext.isReplaying()` or replay suppression inside step lambdas.
6. If you use custom `SerDes`, run one workflow that checkpoints both a successful result and an exception payload.
7. If you use plugins, verify replay-sensitive metadata in at least one replayed child-context scenario.

## Summary

Most upgrades are straightforward:

- `semantics(...)` becomes `semanticsPerRetry(...)`, and old `AT_MOST_ONCE_PER_RETRY` users may also need `RetryStrategies.Presets.NO_RETRY` to preserve `1.x` behavior
- Logger metadata moves to `executionArn`, `operationId`, and `operationName`
- Replay-sensitive logging becomes per-context, `isReplaying()` moves to `DurableContext`, and step logs are no longer replay-suppressed
- Validation failures now throw `IllegalStateException`
- Serialization round-trip problems surface earlier by default, with an opt-out via `withDeserializeAfterSerialization(false)`

If you update those areas first, the `1.x` to `2.x` migration should be low risk.
