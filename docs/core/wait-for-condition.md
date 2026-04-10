## waitForCondition() – Poll Until a Condition is Met

`waitForCondition` repeatedly calls a check function until it signals done. Between polls, the Lambda suspends without consuming compute. State is checkpointed after each check, so progress survives interruptions.

```java
// Poll an order status until it ships
var config = WaitForConditionConfig.<String>builder()
    .initialState("PENDING")
    .build();

var status = ctx.waitForCondition(
    "wait-for-shipment",
    String.class,
    (currentStatus, stepCtx) -> {
        var latest = orderService.getStatus(orderId);
        return "SHIPPED".equals(latest)
            ? WaitForConditionResult.stopPolling(latest)
            : WaitForConditionResult.continuePolling(latest);
    },
    config);
```

The check function receives the current state and a `StepContext`, and returns a `WaitForConditionResult`:
- `WaitForConditionResult.stopPolling(value)` — condition met, return `value` as the final result
- `WaitForConditionResult.continuePolling(value)` — keep polling, pass `value` to the next check

The `initialState` is configured via `WaitForConditionConfig` (`"PENDING"` above) and is passed to the first check invocation.

## waitForConditionAsync() – Non-Blocking Polling

`waitForConditionAsync()` starts polling but returns a `DurableFuture<T>` immediately, allowing other operations to run concurrently.

```java
DurableFuture<String> shipmentFuture = ctx.waitForConditionAsync(
    "wait-for-shipment",
    String.class,
    (status, stepCtx) -> {
        var latest = orderService.getStatus(orderId);
        return "SHIPPED".equals(latest)
            ? WaitForConditionResult.stopPolling(latest)
            : WaitForConditionResult.continuePolling(latest);
    },
    WaitForConditionConfig.<String>builder()
        .initialState("PENDING")
        .build());

// Do other work while polling runs
var invoice = ctx.step("generate-invoice", String.class, stepCtx -> generateInvoice(orderId));

// Block until the condition is met
var shipmentStatus = shipmentFuture.get();
```

## Wait Strategies

The wait strategy controls the delay between polls. By default, `waitForCondition` uses exponential backoff (60 max attempts, 5s initial delay, 300s max delay, 1.5x backoff rate, FULL jitter).

Use `WaitStrategies` to configure a different strategy:

```java
// Fixed 30-second delay, up to 10 attempts
var config = WaitForConditionConfig.<String>builder()
    .waitStrategy(WaitStrategies.fixedDelay(10, Duration.ofSeconds(30)))
    .initialState("PENDING")
    .build();

var result = ctx.waitForCondition("poll-status", String.class, checkFunc, config);
```

```java
// Custom exponential backoff
var config = WaitForConditionConfig.<String>builder()
    .waitStrategy(WaitStrategies.exponentialBackoff(
        20,                          // max attempts
        Duration.ofSeconds(2),       // initial delay
        Duration.ofSeconds(60),      // max delay
        2.0,                         // backoff rate
        JitterStrategy.HALF))        // jitter
    .build();
```

| Factory Method | Description |
|----------------|-------------|
| `WaitStrategies.defaultStrategy()` | Exponential backoff: 60 attempts, 5s initial, 300s max, 1.5x rate, FULL jitter |
| `WaitStrategies.exponentialBackoff(...)` | Custom exponential backoff with configurable parameters |
| `WaitStrategies.fixedDelay(maxAttempts, delay)` | Constant delay between polls |
| `WaitStrategies.Presets.DEFAULT` | Same as `defaultStrategy()`, as a static constant |

## Configuration

`WaitForConditionConfig` holds optional parameters. All fields have sensible defaults, so you only need it when customizing:

```java
var config = WaitForConditionConfig.<String>builder()
    .waitStrategy(WaitStrategies.fixedDelay(10, Duration.ofSeconds(5)))
    .serDes(new CustomSerDes())
    .build();
```

| Option | Default | Description |
|--------|---------|-------------|
| `waitStrategy()` | Exponential backoff (see above) | Controls delay between polls and max attempts |
| `serDes()` | Handler default | Custom serialization for checkpointing state |
| `initialState()` | `null` | Initial state passed to the first check invocation |

## Error Handling

| Exception | When Thrown |
|-----------|-------------|
| `WaitForConditionFailedException` | Max attempts exceeded (thrown by the wait strategy) |
| `SerDesException` | Checkpointed state fails to deserialize on replay |
| User's exception | Check function throws — propagated through `get()` |

```java
try {
    var result = ctx.waitForCondition("poll", String.class, checkFunc);
} catch (WaitForConditionFailedException e) {
    // Max attempts exceeded — condition was never met
}
```

## Custom Wait Strategies

You can write a custom strategy by implementing `WaitForConditionWaitStrategy<T>`:

```java
WaitForConditionWaitStrategy<String> customStrategy = (state, attempt) -> {
    // Vary delay based on state
    if ("ALMOST_READY".equals(state)) {
        return Duration.ofSeconds(2);  // Poll faster when close
    }
    return Duration.ofSeconds(30);     // Otherwise poll slowly
};
```

The strategy receives the current state and attempt number, and returns a `Duration`. Throw `WaitForConditionFailedException` to stop polling with an error.
