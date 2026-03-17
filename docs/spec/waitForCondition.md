# Design: waitForCondition for Durable Execution Java SDK

## Overview

This design adds a `waitForCondition` operation to the Java Durable Execution SDK. The operation periodically checks a user-supplied condition function, using a configurable wait strategy to determine polling intervals and termination. It follows the same checkpoint-and-replay model as existing operations (`step`, `wait`, `invoke`) and mirrors the JavaScript SDK's `waitForCondition` implementation.

## Architecture

### How it works

`waitForCondition` is implemented as a specialized step operation that uses the RETRY checkpoint action for polling iterations:

1. User calls `ctx.waitForCondition(name, resultType, checkFunc, config)`
2. A `WaitForConditionOperation` is created with a unique operation ID
3. On first execution:
   - Checkpoint START with subtype `WAIT_FOR_CONDITION`
   - Execute the check function with `initialState` and a `StepContext`
   - Call the wait strategy with the new state and attempt number
   - If `stopPolling()`: checkpoint SUCCEED with the final state, return it
   - If `continuePolling(delay)`: checkpoint RETRY with the state and delay, poll for READY, then loop
   - If check function throws: checkpoint FAIL, propagate the error
4. On replay:
   - SUCCEEDED: return cached result (skip re-execution)
   - FAILED: re-throw cached error
   - PENDING: wait for READY transition, then resume polling
   - STARTED/READY: resume execution from current attempt and state

This matches the JS SDK's behavior where each polling iteration is a RETRY on the same STEP operation.

### New Classes

```
sdk/src/main/java/software/amazon/lambda/durable/
├── WaitForConditionConfig.java       # Config builder (waitStrategy, initialState, serDes)
├── WaitForConditionWaitStrategy.java  # Functional interface: (T state, int attempt) → WaitForConditionDecision
├── WaitForConditionDecision.java     # Sealed result: continuePolling(Duration) | stopPolling()
├── WaitStrategies.java               # Factory with builder for common patterns
├── operation/
│   └── WaitForConditionOperation.java  # Operation implementation
├── model/
│   └── OperationSubType.java          # Add WAIT_FOR_CONDITION enum value
```

### Class Diagram

```
DurableContext
  ├── waitForCondition(name, Class<T>, checkFunc, config) → T
  ├── waitForCondition(name, TypeToken<T>, checkFunc, config) → T
  ├── waitForConditionAsync(name, Class<T>, checkFunc, config) → DurableFuture<T>
  └── waitForConditionAsync(name, TypeToken<T>, checkFunc, config) → DurableFuture<T>
         │
         ▼
WaitForConditionOperation<T> extends BaseDurableOperation<T>
  ├── start()           → checkpoint START, execute check loop
  ├── replay(existing)  → handle SUCCEEDED/FAILED/PENDING/STARTED/READY
  ├── get()             → block, deserialize result or throw
  └── executeCheckLoop(currentState, attempt)
         │
         ├── calls checkFunc(state, stepContext) → newState
         ├── calls waitStrategy.evaluate(newState, attempt) → WaitForConditionDecision
         │     ├── stopPolling()         → checkpoint SUCCEED
         │     └── continuePolling(delay) → checkpoint RETRY, poll, loop
         └── on error → checkpoint FAIL
```

## Detailed Design

### WaitForConditionWaitStrategy<T> (Functional Interface)

```java
@FunctionalInterface
public interface WaitForConditionWaitStrategy<T> {
    WaitForConditionDecision evaluate(T state, int attempt);
}
```

- `state`: the current state returned by the check function
- `attempt`: 0-based attempt number (first check is attempt 0)
- Returns a `WaitForConditionDecision` indicating whether to continue or stop

### WaitForConditionDecision

```java
public sealed interface WaitForConditionDecision {
    record ContinuePolling(Duration delay) implements WaitForConditionDecision {}
    record StopPolling() implements WaitForConditionDecision {}

    static WaitForConditionDecision continuePolling(Duration delay) {
        return new ContinuePolling(delay);
    }

    static WaitForConditionDecision stopPolling() {
        return new StopPolling();
    }
}
```

Uses Java sealed interfaces for type safety. The `delay` in `ContinuePolling` must be >= 1 second (enforced at construction).

### WaitStrategies (Factory)

```java
public final class WaitStrategies {
    public static <T> Builder<T> builder(Predicate<T> shouldContinuePolling) { ... }

    public static class Builder<T> {
        // Defaults match JS SDK
        private int maxAttempts = 60;
        private Duration initialDelay = Duration.ofSeconds(5);
        private Duration maxDelay = Duration.ofSeconds(300);
        private double backoffRate = 1.5;
        private JitterStrategy jitter = JitterStrategy.FULL;

        public Builder<T> maxAttempts(int maxAttempts) { ... }
        public Builder<T> initialDelay(Duration initialDelay) { ... }
        public Builder<T> maxDelay(Duration maxDelay) { ... }
        public Builder<T> backoffRate(double backoffRate) { ... }
        public Builder<T> jitter(JitterStrategy jitter) { ... }
        public WaitForConditionWaitStrategy<T> build() { ... }
    }
}
```

The built strategy:
1. Calls `shouldContinuePolling.test(state)` — if false, returns `stopPolling()`
2. Checks `attempt >= maxAttempts` — if true, throws `WaitForConditionException`
3. Calculates delay: `min(initialDelay * backoffRate^(attempt-1), maxDelay)`
4. Applies jitter using the existing `JitterStrategy` enum
5. Ensures delay >= 1 second, rounds to nearest integer second
6. Returns `continuePolling(delay)`

### WaitForConditionConfig<T>

```java
public class WaitForConditionConfig<T> {
    private final WaitForConditionWaitStrategy<T> waitStrategy;
    private final T initialState;
    private final SerDes serDes;  // nullable, falls back to DurableConfig default

    public static <T> Builder<T> builder(WaitForConditionWaitStrategy<T> waitStrategy, T initialState) { ... }

    public static class Builder<T> {
        public Builder<T> serDes(SerDes serDes) { ... }
        public WaitForConditionConfig<T> build() { ... }
    }
}
```

`waitStrategy` and `initialState` are required constructor parameters on the builder (not optional setters), so they can never be null.

### WaitForConditionOperation<T>

Extends `BaseDurableOperation<T>`. Key behaviors:

- **start()**: Begins the check loop from `initialState` at attempt 0
- **replay(existing)**: Handles all operation statuses (SUCCEEDED, FAILED, PENDING, STARTED, READY)
- **executeCheckLoop(state, attempt)**: Core polling logic
  - Creates a `StepContext` for the check function
  - Executes check function in the user executor (same pattern as `StepOperation`)
  - Serializes/deserializes state through SerDes (round-trip, matching JS SDK)
  - Calls wait strategy with deserialized state
  - Checkpoints RETRY with `NextAttemptDelaySeconds` or SUCCEED/FAIL
- **get()**: Blocks on completion, deserializes result or throws exception

All checkpoint updates use `OperationType.STEP` and `OperationSubType.WAIT_FOR_CONDITION`.

### DurableContext API Methods

```java
// Sync methods (block until condition met)
public <T> T waitForCondition(String name, Class<T> resultType,
    Function<StepContext, T> checkFunc, WaitForConditionConfig<T> config)

public <T> T waitForCondition(String name, TypeToken<T> typeToken,
    Function<StepContext, T> checkFunc, WaitForConditionConfig<T> config)

// Async methods (return DurableFuture immediately)
public <T> DurableFuture<T> waitForConditionAsync(String name, Class<T> resultType,
    Function<StepContext, T> checkFunc, WaitForConditionConfig<T> config)

public <T> DurableFuture<T> waitForConditionAsync(String name, TypeToken<T> typeToken,
    Function<StepContext, T> checkFunc, WaitForConditionConfig<T> config)
```

The check function signature is `Function<StepContext, T>` rather than `BiFunction<T, StepContext, T>` because the current state is managed internally by the operation. The check function receives the current state via the operation's internal loop — the `StepContext` provides logging and attempt info. Wait, actually looking at the JS SDK more carefully, the check function does receive the current state as a parameter: `(state: T, context) => Promise<T>`. So the Java signature should be `BiFunction<T, StepContext, T>`.

Corrected signature:

```java
public <T> DurableFuture<T> waitForConditionAsync(String name, TypeToken<T> typeToken,
    BiFunction<T, StepContext, T> checkFunc, WaitForConditionConfig<T> config)
```

### OperationSubType Addition

```java
public enum OperationSubType {
    RUN_IN_CHILD_CONTEXT("RunInChildContext"),
    MAP("Map"),
    PARALLEL("Parallel"),
    WAIT_FOR_CALLBACK("WaitForCallback"),
    WAIT_FOR_CONDITION("WaitForCondition");  // NEW
    ...
}
```

### Error Handling

- **Check function throws**: Checkpoint FAIL with serialized error, wrap in `WaitForConditionException`
- **Max attempts exceeded**: `WaitStrategies`-built strategy throws `WaitForConditionException("waitForCondition exceeded maximum attempts (N)")`
- **Custom strategy throws**: Propagated as-is (checkpoint FAIL)
- **SerDes failure**: Wrapped in `SerDesException` (existing pattern)

A new `WaitForConditionException` extends `DurableOperationException` for domain-specific errors.

### Exception Class

```java
public class WaitForConditionException extends DurableOperationException {
    public WaitForConditionException(String message) { ... }
    public WaitForConditionException(Operation operation) { ... }
}
```

## Testing Strategy

### Unit Tests (sdk/src/test/)
- `WaitForConditionDecisionTest`: verify `continuePolling`/`stopPolling` factory methods
- `WaitStrategiesTest`: verify builder defaults, exponential backoff, jitter, max attempts
- `WaitForConditionConfigTest`: verify builder validation
- `WaitForConditionOperationTest`: verify start, replay, error handling

### Integration Tests (sdk-integration-tests/)
- `WaitForConditionIntegrationTest`: end-to-end with `LocalDurableTestRunner`, verify replay across invocations

### Example Tests (examples/)
- `WaitForConditionExample`: demonstrates polling with `WaitStrategies` factory
- `WaitForConditionExampleTest`: verifies example with `LocalDurableTestRunner`

### Testing Framework
- JUnit 5 for all tests
- jqwik for property-based tests (already available in the project's test dependencies — if not, we'll use JUnit 5 parameterized tests with random generators)

## Correctness Properties

### Property 1: WaitForConditionWaitStrategy contract — stopPolling terminates
For any state `s` of type `T` and any attempt number `n >= 1`, if `waitStrategy.evaluate(s, n)` returns `StopPolling`, then `waitForCondition` completes with `s` as the result.

**Validates: Requirements 1.5, 2.1**

### Property 2: WaitStrategies factory — exponential backoff calculation
For any `initialDelay d`, `backoffRate r >= 1`, `maxDelay m >= d`, and attempt `n >= 1` with jitter=NONE, the delay equals `min(d * r^(n-1), m)` rounded to the nearest integer second, with a minimum of 1 second.

**Validates: Requirements 2.3, 2.4**

### Property 3: WaitStrategies factory — max attempts enforcement
For any `maxAttempts N >= 1` and any state where `shouldContinuePolling` returns true, calling the strategy with `attempt >= N` must throw `WaitForConditionException`.

**Validates: Requirements 2.5**

### Property 4: WaitForConditionConfig — required fields validation
Building a `WaitForConditionConfig` without a `waitStrategy` or with a null `initialState` must always throw an exception, regardless of other configuration.

**Validates: Requirements 3.2**

### Property 5: WaitForConditionWaitStrategy receives correct state and attempt
For any sequence of check function invocations, the wait strategy always receives the state returned by the most recent check function call and the correct 1-based attempt number.

**Validates: Requirements 1.3, 2.1**

### Property 6: Operation name validation
For any string that violates `ParameterValidator.validateOperationName` rules, calling `waitForCondition` or `waitForConditionAsync` with that name must throw.

**Validates: Requirements 5.4**

### Property 7: Jitter bounds
For any delay `d` and jitter strategy: NONE produces exactly `d`, FULL produces a value in `[0, d]` (clamped to min 1s), HALF produces a value in `[d/2, d]`.

**Validates: Requirements 2.3**
