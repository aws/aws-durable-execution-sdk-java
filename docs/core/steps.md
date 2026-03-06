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
		.semantics(...)
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
		.semantics(...)        // At-least-once vs at-most-once
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
| `AT_LEAST_ONCE_PER_RETRY` (default) | Re-executes step if interrupted before completion | Idempotent operations (database upserts, API calls with idempotency keys) |
| `AT_MOST_ONCE_PER_RETRY` | Never re-executes; throws `StepInterruptedException` if interrupted | Non-idempotent operations (sending emails, charging payments) |

```java
// Default: at-least-once per retry (step may re-run if interrupted)
var result = ctx.step("idempotent-update", Result.class, 
	stepCtx -> database.upsert(record));

// At-most-once per retry
var result = ctx.step("send-email", Result.class,
	stepCtx -> emailService.send(notification),
	StepConfig.builder()
		.semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
		.build());
```

**Important**: These semantics apply *per retry attempt*, not per overall execution:

- **AT_LEAST_ONCE_PER_RETRY**: The step executes at least once per retry. If the step succeeds but checkpointing fails (e.g., sandbox crash), the step re-executes on replay.
- **AT_MOST_ONCE_PER_RETRY**: A checkpoint is created before execution. If failure occurs after checkpoint but before completion, the step is skipped on replay and `StepInterruptedException` is thrown.

To achieve step-level at-most-once semantics, combine with a no-retry strategy:

```java
// True at-most-once: step executes at most once, ever
var result = ctx.step("charge-payment", Result.class,
	stepCtx -> paymentService.charge(amount),
	StepConfig.builder()
		.semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
		.retryStrategy(RetryStrategies.Presets.NO_RETRY)
		.build());
```

Without this, a step using `AT_MOST_ONCE_PER_RETRY` with retries enabled could still execute multiple times across different retry attempts.

### Generic Types

When a step returns a parameterized type like `List<User>`, use `TypeToken` to preserve the type information:

```java
var users = ctx.step("fetch-users", new TypeToken<List<User>>() {}, 
	stepCtx -> userService.getAllUsers());

var orderMap = ctx.step("fetch-orders", new TypeToken<Map<String, Order>>() {},
	stepCtx -> orderService.getOrdersByCustomer());
```

This is needed for the SDK to deserialize a checkpointed result and get the exact type to reconstruct. See [TypeToken and Type Erasure](docs/internal-design.md#typetoken-and-type-erasure) for technical details. 