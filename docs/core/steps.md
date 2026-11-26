## step() – Execute with Checkpointing

Steps execute your code and checkpoint the result. On replay, results from completed checkpoints are returned without re-execution.

```java
// Basic step (blocks until complete)
var result = ctx.step("fetch-user", User.class, stepCtx -> userService.getUser(userId));

// Step with custom configuration (retries, semantics, serialization)
var result = ctx.step("call-api", Response.class, 
	stepCtx -> externalApi.call(request),
	StepConfig.builder()
		.retryStrategy(...)
		.semanticsPerRetry(...)
		.build());
```

See [Step Configuration](#step-configuration) for retry strategies, delivery semantics, and per-step serialization options.

### stepAsync() and DurableFuture – Concurrent Operations

`stepAsync()` starts a step in the background and returns a `DurableFuture<T>`. This enables concurrent execution patterns.

```java
// Start multiple operations concurrently
DurableFuture<User> userFuture = ctx.stepAsync("fetch-user", User.class, 
	stepCtx -> userService.getUser(userId));
DurableFuture<List<Order>> ordersFuture = ctx.stepAsync("fetch-orders", 
	new TypeToken<List<Order>>() {}, stepCtx -> orderService.getOrders(userId));

// Both operations run concurrently
// Block and get results when needed
User user = userFuture.get();
List<Order> orders = ordersFuture.get();
```

## Step Configuration

Configure step behavior with `StepConfig`:

```java
ctx.step("my-step", Result.class, stepCtx -> doWork(),
	StepConfig.builder()
		.retryStrategy(...)    // How to handle failures
		.semanticsPerRetry(...)        // At-least-once vs at-most-once
		.serDes(...)           // Custom serialization
		.build());
```

### Retry Strategies

Configure how steps handle transient failures:

```java
// No retry - fail immediately (default)
var noRetries = StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build()

// Exponential backoff with jitter
var customRetries = StepConfig.builder()
	.retryStrategy(RetryStrategies.exponentialBackoff(
		5,                        // max attempts
		Duration.ofSeconds(2),    // initial delay  
		Duration.ofSeconds(30),   // max delay
		2.0,                      // backoff multiplier
		JitterStrategy.FULL))     // randomize delays
	.build()
```

### Step-Retry Semantics

Control how steps behave when interrupted mid-execution:

| Semantic | Behavior | Use Case |
|----------|----------|----------|
| `AT_LEAST_ONCE` (default) | Re-executes step if interrupted before completion | Idempotent operations (database upserts, API calls with idempotency keys) |
| `AT_MOST_ONCE` | Re-executes step once per retry if interrupted. Throws `StepInterruptedException` if retries exhausted | Non-idempotent operations (sending emails, charging payments) |
| `AT_LEAST_ONCE_ASYNC` | Like `AT_LEAST_ONCE`, but returns the result without waiting for the SUCCEED checkpoint | Idempotent operations where latency matters |

```java
// Default: at-least-once per retry (step may re-run if interrupted)
var result = ctx.step("idempotent-update", Result.class, 
	stepCtx -> database.upsert(record));

// At-most-once per retry
var result = ctx.step("send-email", Result.class,
	stepCtx -> emailService.send(notification),
	StepConfig.builder()
		.semanticsPerRetry(StepSemantics.AT_MOST_ONCE)
		.build());
```

`AT_LEAST_ONCE_PER_RETRY` and `AT_MOST_ONCE_PER_RETRY` are deprecated names of `AT_LEAST_ONCE` and `AT_MOST_ONCE`. They have the same behavior.

**Important**: These semantics apply *per retry attempt*, not per overall execution:

- **AT_LEAST_ONCE**: The step executes at least once per retry. If the step succeeds but checkpointing fails (e.g., sandbox crash), the step re-executes on replay.
- **AT_MOST_ONCE**: A checkpoint is created before execution. If failure occurs after checkpoint but before completion, the step is re-executed on a new retry attempt. `StepInterruptedException` is thrown if retries are exhausted.
- **AT_LEAST_ONCE_ASYNC**: Like `AT_LEAST_ONCE`, but the step returns its result without waiting for the SUCCEED checkpoint. So the step has lower latency. The code after the step can run before the result is saved. Checkpoints are sent in order, and the invocation does not end or suspend before the service answers the SUCCEED checkpoint. If the invocation ends before the save, the step re-executes on replay. On replay the step can return a different value, and later steps run again with it. So do not use this mode for a step whose result becomes an idempotency key or a client token for a later step. A failed attempt still waits for its RETRY or FAIL checkpoint.

To achieve step-level at-most-once semantics, combine with a no-retry strategy:

```java
// True at-most-once: step executes at most once, ever
var result = ctx.step("charge-payment", Result.class,
	stepCtx -> paymentService.charge(amount),
	StepConfig.builder()
		.semanticsPerRetry(StepSemantics.AT_MOST_ONCE)
		.retryStrategy(RetryStrategies.Presets.NO_RETRY)
		.build());
```

Without this, a step using `AT_MOST_ONCE` with retries enabled could still execute multiple times across different retry attempts.

### Default Step Semantics

`DurableConfig.withDefaultStepSemantics()` sets the semantics of every step that does not set its own. It applies in every context of the execution: child contexts, map and parallel items, and steps that the SDK creates, such as the submitter step of `waitForCallback`. A step's own `semanticsPerRetry()` wins. Without the option, the default stays `AT_LEAST_ONCE`.

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
        .withDefaultStepSemantics(StepSemantics.AT_LEAST_ONCE_ASYNC)
        .build();
}
```

With `AT_LEAST_ONCE_ASYNC` as the default, set `AT_LEAST_ONCE` on each step whose result becomes an idempotency key or a client token for a later step.

### Generic Types

When a step returns a parameterized type like `List<User>`, use `TypeToken` to preserve the type information:

```java
var users = ctx.step("fetch-users", new TypeToken<List<User>>() {}, 
	stepCtx -> userService.getAllUsers());

var orderMap = ctx.step("fetch-orders", new TypeToken<Map<String, Order>>() {},
	stepCtx -> orderService.getOrdersByCustomer());
```

This is needed for the SDK to deserialize a checkpointed result and get the exact type to reconstruct. See [TypeToken and Type Erasure](../design.md#custom-serdes-and-typetoken) for technical details. 