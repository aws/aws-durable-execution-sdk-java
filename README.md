# AWS Lambda Durable Execution SDK for Java

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/java-%3E%3D17-brightgreen)](https://openjdk.org/)

Build resilient, long-running AWS Lambda functions that automatically checkpoint progress and resume after failures. Durable functions can run for up to one year while you pay only for active compute time.

## Key Features

- **Automatic Checkpointing** – Progress is saved after each step; failures resume from the last checkpoint
- **Cost-Effective Waits** – Suspend execution for minutes, hours, or days without compute charges
- **Configurable Retries** – Built-in retry strategies with exponential backoff and jitter
- **Replay Safety** – Functions deterministically resume from checkpoints after interruptions
- **Type Safety** – Full generic type support for step results

## How It Works

Your durable function extends `DurableHandler<I, O>` and implements `handleRequest(I input, DurableContext ctx)`. The `DurableContext` is your interface to durable operations:

- `ctx.step()` – Execute code and checkpoint the result
- `ctx.stepAsync()` – Start concurrent operations  
- `ctx.wait()` – Suspend execution without compute charges
- `ctx.createCallback()` – Wait for external events (approvals, webhooks)

## Quick Start

### Installation

```xml
<dependency>
    <groupId>com.amazonaws.lambda</groupId>
    <artifactId>aws-durable-execution-sdk-java</artifactId>
    <version>VERSION</version>
</dependency>
```

### Your First Durable Function

```java
public class OrderProcessor extends DurableHandler<Order, OrderResult> {

    @Override
    protected OrderResult handleRequest(Order order, DurableContext ctx) {
        // Step 1: Validate and reserve inventory
        var reservation = ctx.step("reserve-inventory", Reservation.class, 
            () -> inventoryService.reserve(order.getItems()));

        // Step 2: Process payment
        var payment = ctx.step("process-payment", Payment.class,
            () -> paymentService.charge(order.getPaymentMethod(), order.getTotal()));

        // Wait for warehouse processing (no compute charges)
        ctx.wait(Duration.ofHours(2));

        // Step 3: Confirm shipment
        var shipment = ctx.step("confirm-shipment", Shipment.class,
            () -> shippingService.ship(reservation, order.getAddress()));

        return new OrderResult(order.getId(), shipment.getTrackingNumber());
    }
}
```

## Core Operations

### step() – Execute with Checkpointing

Steps execute your code and checkpoint the result. On replay, results from completed checkpoints are returned without re-execution.

```java
// Basic step (blocks until complete)
var result = ctx.step("fetch-user", User.class, () -> userService.getUser(userId));

// Step with custom configuration (retries, semantics, serialization)
var result = ctx.step("call-api", Response.class, 
    () -> externalApi.call(request),
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
    () -> userService.getUser(userId));
DurableFuture<List<Order>> ordersFuture = ctx.stepAsync("fetch-orders", 
    new TypeToken<List<Order>>() {}, () -> orderService.getOrders(userId));

// Both operations run concurrently
// Block and get results when needed
User user = userFuture.get();
List<Order> orders = ordersFuture.get();
```

### wait() – Suspend Without Cost

Waits suspend the function and resume after the specified duration. You're not charged during suspension.

```java
// Wait 30 minutes
ctx.wait(Duration.ofMinutes(30));

// Named wait (useful for debugging)
ctx.wait("cooling-off-period", Duration.ofDays(7));
```

### createCallback() – Wait for External Events

Callbacks suspend execution until an external system sends a result. Use this for human approvals, webhooks, or any event-driven workflow.

```java
// Create a callback and get the ID to share with external systems
DurableCallbackFuture<String> callback = ctx.createCallback("approval", String.class);

// Send the callback ID to an external system within a step
ctx.step("send-notification", String.class, () -> {
    notificationService.sendApprovalRequest(callback.callbackId(), requestDetails);
    return "notification-sent";
});

// Suspend until the external system calls back with a result
String approvalResult = callback.get();
```

The external system completes the callback by calling the Lambda Durable Functions API with the callback ID and result payload.

#### Callback Configuration

Configure timeouts and serialization to handle cases where callbacks are never completed or need custom deserialization:

```java
var config = CallbackConfig.builder()
    .timeout(Duration.ofHours(24))        // Max time to wait for callback
    .heartbeatTimeout(Duration.ofHours(1)) // Max time between heartbeats
    .serDes(new CustomSerDes())           // Custom serialization/deserialization
    .build();

var callback = ctx.createCallback("approval", String.class, config);
```

| Option | Description |
|--------|-------------|
| `timeout()` | Maximum duration to wait for the callback to complete |
| `heartbeatTimeout()` | Maximum duration between heartbeat signals from the external system |
| `serDes()` | Custom SerDes for deserializing callback results (e.g., encryption, custom formats) |

#### Callback Exceptions

| Exception | When Thrown |
|-----------|-------------|
| `CallbackTimeoutException` | Callback exceeded its timeout duration |
| `CallbackFailedException` | External system sent an error response |

```java
try {
    var result = callback.get();
} catch (CallbackTimeoutException e) {
    // Callback timed out - implement fallback logic
} catch (CallbackFailedException e) {
    // External system reported an error
}
```

## Step Configuration

Configure step behavior with `StepConfig`:

```java
ctx.step("my-step", Result.class, () -> doWork(),
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
    () -> database.upsert(record));

// At-most-once per retry
var result = ctx.step("send-email", Result.class,
    () -> emailService.send(notification),
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
    () -> paymentService.charge(amount),
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
    () -> userService.getAllUsers());

var orderMap = ctx.step("fetch-orders", new TypeToken<Map<String, Order>>() {},
    () -> orderService.getOrdersByCustomer());
```

This is needed for the SDK to deserialize a checkpointed result and get the exact type to reconstruct. See [TypeToken and Type Erasure](docs/internal-design.md#typetoken-and-type-erasure) for technical details. 

## Configuration

Customize SDK behavior by overriding `createConfiguration()` in your handler:

```java
public class OrderProcessor extends DurableHandler<Order, OrderResult> {

    @Override
    protected DurableConfig createConfiguration() {
        // Custom Lambda client with connection pooling
        var lambdaClient = LambdaClient.builder()
            .httpClient(ApacheHttpClient.builder()
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(30))
                .build())
            .build();

        return DurableConfig.builder()
            .withLambdaClient(lambdaClient)
            .withSerDes(new MyCustomSerDes())           // Custom serialization
            .withExecutorService(Executors.newFixedThreadPool(10))  // Custom thread pool
            .withLoggerConfig(LoggerConfig.withReplayLogging())     // Enable replay logs
            .build();
    }

    @Override
    protected OrderResult handleRequest(Order order, DurableContext ctx) {
        // Your handler logic
    }
}
```

| Option | Description | Default |
|--------|-------------|---------|
| `withLambdaClient()` | Custom AWS Lambda client | Auto-configured Lambda client |
| `withSerDes()` | Serializer for step results | Jackson with default settings |
| `withExecutorService()` | Thread pool for async step execution | Cached daemon thread pool |
| `withLoggerConfig()` | Logger behavior configuration | Suppress logs during replay |

## Logging

The SDK provides a `DurableLogger` via `ctx.getLogger()` that automatically includes execution metadata in log entries and suppresses duplicate logs during replay.

### Basic Usage

```java
@Override
protected OrderResult handleRequest(Order order, DurableContext ctx) {
    ctx.getLogger().info("Processing order: {}", order.getId());
    
    var result = ctx.step("validate", String.class, () -> {
        ctx.getLogger().debug("Validating order details");
        return validate(order);
    });
    
    ctx.getLogger().info("Order processed successfully");
    return new OrderResult(result);
}
```

### Log Output

Logs include execution context via MDC (works with any SLF4J-compatible logging framework):

```json
{
  "timestamp": "2024-01-15T10:30:00.000Z",
  "level": "INFO",
  "message": "Processing order: ORD-123",
  "durableExecutionArn": "arn:aws:lambda:us-east-1:123456789:function:order-processor:exec-abc123",
  "requestId": "a1b2c3d4-5678-90ab-cdef-example12345",
  "operationId": "1",
  "operationName": "validate"
}
```

### Replay Behavior

By default, logs are suppressed during replay to avoid duplicates:

```
First Invocation:
  [INFO] Processing order: ORD-123          ✓ Logged
  [DEBUG] Validating order details          ✓ Logged

Replay (after wait):
  [INFO] Processing order: ORD-123          ✗ Suppressed (already logged)
  [DEBUG] Validating order details          ✗ Suppressed
  [INFO] Continuing after wait              ✓ Logged (new code path)
```

To log during replay (e.g., for debugging):

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
        .withLoggerConfig(LoggerConfig.withReplayLogging())
        .build();
}
```

## Error Handling

The SDK throws specific exceptions to help you handle different failure scenarios:

| Exception | When Thrown | How to Handle |
|-----------|-------------|---------------|
| `StepFailedException` | Step exhausted all retry attempts | Catch to implement fallback logic or let execution fail |
| `StepInterruptedException` | `AT_MOST_ONCE` step was interrupted before completion | Implement manual recovery (check if operation completed externally) |
| `CallbackTimeoutException` | Callback exceeded its timeout duration | Implement fallback logic or escalation |
| `CallbackFailedException` | External system sent an error response to the callback | Handle the error or propagate failure |
| `NonDeterministicExecutionException` | Code changed between original execution and replay | Fix code to maintain determinism; don't change step order/names |

```java
try {
    var result = ctx.step("charge-payment", Payment.class,
        () -> paymentService.charge(amount),
        StepConfig.builder()
            .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
            .build());
} catch (StepInterruptedException e) {
    // Step started but we don't know if it completed
    // Check payment status externally before retrying
    var status = paymentService.checkStatus(transactionId);
    if (status.isPending()) {
        throw e; // Let it fail - manual intervention needed
    }
}
```

## Testing

The SDK includes testing utilities for both local development and cloud-based integration testing.

### Installation

```xml
<dependency>
    <groupId>com.amazonaws.lambda</groupId>
    <artifactId>aws-durable-execution-sdk-java-testing</artifactId>
    <version>VERSION</version>
    <scope>test</scope>
</dependency>
```

### Local Testing

```java
@Test
void testOrderProcessing() {
    var handler = new OrderProcessor();
    var runner = LocalDurableTestRunner.create(Order.class, handler);

    var result = runner.runUntilComplete(new Order("order-123", items));

    assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    assertNotNull(result.getResult(OrderResult.class).getTrackingNumber());
}
```

You can also pass a lambda directly instead of a handler instance:

```java
var runner = LocalDurableTestRunner.create(Order.class, (order, ctx) -> {
    var result = ctx.step("process", String.class, () -> "done");
    return new OrderResult(order.getId(), result);
});
```

### Inspecting Operations

```java
var result = runner.runUntilComplete(input);

// Verify specific step completed
var paymentOp = result.getOperation("process-payment");
assertNotNull(paymentOp);
assertEquals(OperationStatus.SUCCEEDED, paymentOp.getStatus());

// Get step result
var paymentResult = paymentOp.getStepResult(Payment.class);
assertNotNull(paymentResult.getTransactionId());

// Inspect all operations
List<TestOperation> succeeded = result.getSucceededOperations();
List<TestOperation> failed = result.getFailedOperations();
```

### Controlling Time in Tests

By default, `runUntilComplete()` skips wait durations. For testing time-dependent logic, disable this:

```java
var runner = LocalDurableTestRunner.create(Order.class, handler)
    .withSkipTime(false);  // Don't auto-advance time

var result = runner.run(input);
assertEquals(ExecutionStatus.PENDING, result.getStatus());  // Blocked on wait

runner.advanceTime();  // Manually advance past the wait
result = runner.run(input);
assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
```

### Cloud Testing

Test against deployed Lambda functions:

```java
var runner = CloudDurableTestRunner.create(
    "arn:aws:lambda:us-east-1:123456789012:function:order-processor:$LATEST",
    Order.class,
    OrderResult.class);

var result = runner.run(new Order("order-123", items));
assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
```

## Deployment

See [examples/README.md](./examples/README.md) for complete instructions on local testing, deployment, invoking functions, and running cloud integration tests.

## Documentation

- [AWS Lambda Durable Functions](https://docs.aws.amazon.com/lambda/latest/dg/durable-functions.html) – Official AWS documentation
- [Durable Execution SDK Guide](https://docs.aws.amazon.com/lambda/latest/dg/durable-execution-sdk.html) – SDK usage guide
- [Best Practices](https://docs.aws.amazon.com/lambda/latest/dg/durable-best-practices.html) – Patterns and recommendations

## Related SDKs

- [JavaScript/TypeScript SDK](https://github.com/aws/aws-durable-execution-sdk-js)
- [Python SDK](https://github.com/aws/aws-durable-execution-sdk-python)

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for information about reporting security issues.

## License

This project is licensed under the Apache-2.0 License. See [LICENSE](LICENSE) for details.
