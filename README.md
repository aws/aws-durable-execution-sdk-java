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

Steps execute your code and checkpoint the result. On replay, cached results are returned without re-execution.

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

`stepAsync()` starts a step in the background and returns a `DurableFuture<T>` immediately. This enables concurrent execution patterns.

```java
// Start multiple operations concurrently
DurableFuture<User> userFuture = ctx.stepAsync("fetch-user", User.class, 
    () -> userService.getUser(userId));
DurableFuture<List<Order>> ordersFuture = ctx.stepAsync("fetch-orders", 
    new TypeToken<List<Order>>() {}, () -> orderService.getOrders(userId));

// Both operations run in parallel
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
StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build()

// Exponential backoff with jitter
StepConfig.builder()
    .retryStrategy(RetryStrategies.exponentialBackoff(
        5,                        // max attempts
        Duration.ofSeconds(2),    // initial delay  
        Duration.ofSeconds(30),   // max delay
        2.0,                      // backoff multiplier
        JitterStrategy.FULL))     // randomize delays
    .build()
```

### Step Semantics

Control how steps behave when interrupted mid-execution:

| Semantic | Behavior | Use Case |
|----------|----------|----------|
| `AT_LEAST_ONCE_PER_RETRY` (default) | Re-executes step if interrupted before completion | Idempotent operations (database upserts, API calls with idempotency keys) |
| `AT_MOST_ONCE_PER_RETRY` | Never re-executes; throws `StepInterruptedException` if interrupted | Non-idempotent operations (sending emails, charging payments) |

```java
// Default: at-least-once (step may re-run if interrupted)
var result = ctx.step("idempotent-update", Result.class, 
    () -> database.upsert(record));

// At-most-once: step will not re-run if interrupted
var result = ctx.step("send-email", Result.class,
    () -> emailService.send(notification),
    StepConfig.builder()
        .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
        .build());
```

With `AT_MOST_ONCE_PER_RETRY`, if a step starts but the function is interrupted before the result is checkpointed, the SDK throws `StepInterruptedException` on replay instead of re-executing. Handle this to implement your own recovery logic.

### Generic Types

For complex generic types like `List<User>`, use `TypeToken`:

```java
var users = ctx.step("fetch-users", new TypeToken<List<User>>() {}, 
    () -> userService.getAllUsers());
```

## Configuration

Customize SDK behavior by overriding `createConfiguration()` in your handler:

```java
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
        .withDurableExecutionClient(new LambdaDurableFunctionsClient(lambdaClient))
        .withSerDes(new MyCustomSerDes())           // Custom serialization
        .withExecutorService(Executors.newFixedThreadPool(10))  // Custom thread pool
        .build();
}
```

| Option | Description | Default |
|--------|-------------|---------|
| `withDurableExecutionClient()` | Client for checkpoint operations | Auto-configured Lambda client |
| `withSerDes()` | Serializer for step results | Jackson with default settings |
| `withExecutorService()` | Thread pool for async step execution | Cached daemon thread pool |

## Error Handling

The SDK throws specific exceptions to help you handle different failure scenarios:

| Exception | When Thrown | How to Handle |
|-----------|-------------|---------------|
| `StepFailedException` | Step exhausted all retry attempts | Catch to implement fallback logic or let execution fail |
| `StepInterruptedException` | `AT_MOST_ONCE` step was interrupted before completion | Implement manual recovery (check if operation completed externally) |
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
var runner = LocalDurableTestRunner.create(Order.class, handler::handleRequest)
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
    "arn:aws:lambda:us-east-1:123456789012:function:order-processor",
    Order.class,
    OrderResult.class);

var result = runner.run(new Order("order-123", items));
assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
```

## Deployment

The [examples](./examples) module includes a complete SAM template and deployment instructions.

```bash
cd examples
mvn clean package
sam build
sam deploy --guided
```

Key deployment requirements:
- `DurableConfig` in SAM template with `ExecutionTimeout` and `RetentionPeriodInDays`
- IAM permissions for `lambda:CheckpointDurableExecutions` and `lambda:GetDurableExecutionState`

See [examples/template.yaml](./examples/template.yaml) and [examples/README.md](./examples/README.md) for details.

## Examples

| Example | Description |
|---------|-------------|
| [SimpleStepExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/SimpleStepExample.java) | Basic sequential steps |
| [WaitExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/WaitExample.java) | Using wait operations |
| [RetryExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/RetryExample.java) | Configuring retry strategies |
| [GenericTypesExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/GenericTypesExample.java) | Working with generic types |
| [CustomConfigExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/CustomConfigExample.java) | Custom Lambda client and SerDes |
| [WaitAtLeastExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/WaitAtLeastExample.java) | Concurrent stepAsync() with wait() |
| [RetryInProcessExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/RetryInProcessExample.java) | In-process retry with concurrent operations |
| [WaitAtLeastInProcessExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/WaitAtLeastInProcessExample.java) | Wait completes before async step (no suspension) |

## Requirements

- Java 17+
- AWS SDK for Java v2

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
