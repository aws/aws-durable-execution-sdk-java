# Design: waitForCondition for Durable Execution Java SDK

## Overview

`waitForCondition` is a durable operation that repeatedly polls a user-supplied check function until it signals done. Between polls, the Lambda suspends without consuming compute. State is checkpointed after each check, so progress survives interruptions. It follows the same checkpoint-and-replay model as existing operations (`step`, `wait`, `invoke`) and mirrors the JavaScript SDK's `waitForCondition` implementation.

## Architecture

### How it works

1. User calls `ctx.waitForCondition(name, resultType, checkFunc)` (or with optional config)
2. A `WaitForConditionOperation` is created with a unique operation ID
3. On first execution:
   - Checkpoint START with subtype `WAIT_FOR_CONDITION`
   - Execute the check function with `initialState` and a `StepContext`
   - If check function returns `WaitForConditionResult.stopPolling(value)`: checkpoint SUCCEED, return value
   - If check function returns `WaitForConditionResult.continuePolling(value)`: call wait strategy to compute delay, checkpoint RETRY with state and delay, poll for READY, then loop
   - If check function throws: checkpoint FAIL, propagate the error
4. On replay:
   - SUCCEEDED: return cached result (skip re-execution)
   - FAILED: re-throw cached error
   - PENDING: wait for READY transition, then resume polling
   - STARTED/READY: resume execution from current attempt and state

### File Structure

```
sdk/src/main/java/software/amazon/lambda/durable/
├── WaitForConditionResult.java          # Check function return type (value + isDone)
├── WaitForConditionConfig.java          # Optional config (wait strategy, custom SerDes)
├── retry/
│   ├── WaitForConditionWaitStrategy.java  # Functional interface: (T state, int attempt) → Duration
│   └── WaitStrategies.java               # Factory methods + Presets.DEFAULT
├── operation/
│   └── WaitForConditionOperation.java    # Operation implementation
├── exception/
│   └── WaitForConditionException.java    # Thrown when max attempts exceeded
└── model/
    └── OperationSubType.java             # WAIT_FOR_CONDITION enum value
```

### Class Diagram

```
DurableContext (interface)
  ├── waitForCondition(name, Class<T>, checkFunc) → T
  ├── waitForCondition(name, Class<T>, checkFunc, config) → T
  ├── waitForCondition(name, TypeToken<T>, checkFunc) → T
  ├── waitForCondition(name, TypeToken<T>, checkFunc, config) → T
  ├── waitForConditionAsync(name, Class<T>, checkFunc) → DurableFuture<T>
  ├── waitForConditionAsync(name, Class<T>, checkFunc, config) → DurableFuture<T>
  ├── waitForConditionAsync(name, TypeToken<T>, checkFunc) → DurableFuture<T>
  └── waitForConditionAsync(name, TypeToken<T>, checkFunc, config) → DurableFuture<T>
         │
         ▼
WaitForConditionOperation<T> extends BaseDurableOperation<T>
  ├── start()           → checkpoint START, execute check loop
  ├── replay(existing)  → handle SUCCEEDED/FAILED/PENDING/STARTED/READY
  ├── get()             → block, deserialize result or throw
  └── executeCheckLogic(currentState, attempt)
         │
         ├── calls checkFunc(state, stepContext) → WaitForConditionResult<T>
         │     ├── stopPolling(value)      → checkpoint SUCCEED
         │     └── continuePolling(value)  → call waitStrategy, checkpoint RETRY, poll, loop
         └── on error → checkpoint FAIL
```

## Detailed Design

### WaitForConditionResult\<T\> (Record)

```java
public record WaitForConditionResult<T>(T value, boolean isDone) {
    public static <T> WaitForConditionResult<T> stopPolling(T value);
    public static <T> WaitForConditionResult<T> continuePolling(T value);
}
```

Returned by the check function to signal whether the condition is met:
- `stopPolling(value)`: condition met, return `value` as the final result
- `continuePolling(value)`: keep polling, pass `value` to the next check and to the wait strategy

### WaitForConditionWaitStrategy\<T\> (Functional Interface)

```java
@FunctionalInterface
public interface WaitForConditionWaitStrategy<T> {
    Duration evaluate(T state, int attempt);
}
```

Computes the delay before the next poll. Only called when the check function returns `continuePolling`. Throws `WaitForConditionException` when max attempts exceeded.

- `state`: the current state from the check function
- `attempt`: 0-based attempt number
- Returns: `Duration` delay before next poll

Built-in strategies (from `WaitStrategies`) ignore the state parameter and compute delays based solely on the attempt number.

### WaitStrategies (Factory)

```java
public final class WaitStrategies {

    public static class Presets {
        public static final WaitForConditionWaitStrategy<?> DEFAULT = ...;
    }

    public static <T> WaitForConditionWaitStrategy<T> defaultStrategy();

    public static <T> WaitForConditionWaitStrategy<T> exponentialBackoff(
            int maxAttempts, Duration initialDelay, Duration maxDelay,
            double backoffRate, JitterStrategy jitter);

    public static <T> WaitForConditionWaitStrategy<T> fixedDelay(
            int maxAttempts, Duration fixedDelay);
}
```

Mirrors `RetryStrategies` with static factory methods and a `Presets` class.

Default parameters (matching JS SDK): maxAttempts=60, initialDelay=5s, maxDelay=300s, backoffRate=1.5, jitter=FULL.

Delay formula: `max(1, round(jitter(min(initialDelay × backoffRate^attempt, maxDelay))))`

Validation: maxAttempts > 0, initialDelay >= 1s, maxDelay >= 1s, backoffRate >= 1.0, jitter not null.

### WaitForConditionConfig\<T\>

```java
public class WaitForConditionConfig<T> {
    public static <T> Builder<T> builder();

    public WaitForConditionWaitStrategy<T> waitStrategy();  // defaults to WaitStrategies.defaultStrategy()
    public SerDes serDes();                                  // defaults to null (uses handler default)
    public Builder<T> toBuilder();                           // for internal SerDes injection

    public static class Builder<T> {
        public Builder<T> waitStrategy(WaitForConditionWaitStrategy<T> waitStrategy);
        public Builder<T> serDes(SerDes serDes);
        public WaitForConditionConfig<T> build();
    }
}
```

Holds only optional parameters. The required parameter `checkFunc` is direct method argument on `DurableContext.waitForCondition()`.

### DurableContext API (8 signatures)

Delegation chain (same pattern as `step()`):
- All sync methods → corresponding async method → `.get()`
- All Class-based methods → TypeToken-based via `TypeToken.get(resultType)`
- All no-config methods → config method with `WaitForConditionConfig.builder().build()`
- Core method: `waitForConditionAsync(name, TypeToken<T>, checkFunc, config)`

The core method validates: `name` (via `ParameterValidator`), `typeToken` not null, `checkFunc` not null, `config` not null.

### WaitForConditionOperation\<T\>

Extends `BaseDurableOperation<T>`. Key behaviors:

- **start()**: Begins the check loop from `initialState` at attempt 0
- **replay(existing)**: Handles all operation statuses
- **resumeCheckLoop(existing)**: Deserializes checkpointed state (throws `SerDesException` if corrupt)
- **executeCheckLogic(state, attempt)**: Runs check function on user executor, handles `WaitForConditionResult`, checkpoints accordingly
- **get()**: Blocks on completion, deserializes result or reconstructs and throws the original exception

All checkpoint updates use `OperationType.STEP` and `OperationSubType.WAIT_FOR_CONDITION`.

### Error Handling

| Scenario | Behavior |
|----------|----------|
| Check function throws | Checkpoint FAIL, propagate via `get()` |
| Strategy throws `WaitForConditionException` | Checkpoint FAIL, propagate via `get()` |
| Checkpoint data fails to deserialize on replay | Throws `SerDesException` (propagates to handler) |
| `SuspendExecutionException` during check | Re-thrown (Lambda suspension) |
| `UnrecoverableDurableExecutionException` during check | Terminates execution |

## Usage Examples

### Minimal (default config)

```java
var result = ctx.waitForCondition(
    "wait-for-shipment",
    String.class,
    (status, stepCtx) -> {
        var currentStatus = getOrderStatus(orderId);
        return "SHIPPED".equals(currentStatus)
            ? WaitForConditionResult.stopPolling(currentStatus)
            : WaitForConditionResult.continuePolling(currentStatus);
    },
    "PENDING");
```

### Custom strategy

```java
var config = WaitForConditionConfig.<String>builder()
    .waitStrategy(WaitStrategies.fixedDelay(10, Duration.ofSeconds(30)))
    .build();

var result = ctx.waitForCondition(
    "wait-for-approval",
    String.class,
    (status, stepCtx) -> {
        var current = checkApprovalStatus(requestId);
        return "APPROVED".equals(current)
            ? WaitForConditionResult.stopPolling(current)
            : WaitForConditionResult.continuePolling(current);
    },
    "PENDING_REVIEW",
    config);
```

## Testing

### Unit Tests
- `WaitForConditionOperationTest`: replay (SUCCEEDED, FAILED, STARTED, READY, PENDING, unexpected status), null checkpoint data, corrupt checkpoint data
- `WaitStrategiesTest`: exponential backoff formula, max delay cap, max attempts enforcement, jitter bounds, validation, factory methods, presets
- `WaitForConditionConfigTest`: default strategy, custom strategy, SerDes, toBuilder

### Integration Tests
- `WaitForConditionIntegrationTest`: basic polling, custom strategy, max attempts exceeded, check function error (with error type verification), replay across invocations, property tests for state/attempt correctness

### Example
- `WaitForConditionExample`: simulates polling order shipment status (PENDING → PROCESSING → SHIPPED)
