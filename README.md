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
// Basic step
var result = ctx.step("fetch-user", User.class, () -> userService.getUser(userId));

// Step with retry strategy
var result = ctx.step("call-api", Response.class, 
    () -> externalApi.call(request),
    StepConfig.builder()
        .retryStrategy(RetryStrategies.Presets.DEFAULT)
        .build());
```

### wait() – Suspend Without Cost

Waits suspend the function and resume after the specified duration. You're not charged during suspension.

```java
// Wait 30 minutes
ctx.wait(Duration.ofMinutes(30));

// Named wait (useful for debugging)
ctx.wait("cooling-off-period", Duration.ofDays(7));
```

### Retry Strategies

Configure how steps handle transient failures:

```java
// No retry (fail immediately)
StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build()

// Default exponential backoff
StepConfig.builder().retryStrategy(RetryStrategies.Presets.DEFAULT).build()

// Custom strategy
StepConfig.builder()
    .retryStrategy(RetryStrategies.exponentialBackoff()
        .maxAttempts(5)
        .initialDelay(Duration.ofSeconds(1))
        .maxDelay(Duration.ofMinutes(1))
        .build())
    .build()
```

### Generic Types

For complex generic types like `List<User>`, use `TypeToken`:

```java
var users = ctx.step("fetch-users", new TypeToken<List<User>>() {}, 
    () -> userService.getAllUsers());
```

## Testing

The SDK includes testing utilities for fast, local development without deploying to AWS.

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
    var runner = LocalDurableTestRunner.create(Order.class, handler::handleRequest);

    var result = runner.runUntilComplete(new Order("order-123", items));

    assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    assertNotNull(result.getResult(OrderResult.class).getTrackingNumber());
}
```

### Inspecting Operations

```java
var result = runner.runUntilComplete(input);

// Check all operations
for (var op : result.getOperations()) {
    System.out.println(op.getName() + ": " + op.getStatus());
}

// Get specific operation
var paymentOp = result.getOperation("process-payment");
assertEquals(OperationStatus.SUCCEEDED, paymentOp.getStatus());
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

### SAM Template

```yaml
Resources:
  MyDurableFunction:
    Type: AWS::Serverless::Function
    Properties:
      PackageType: Image
      FunctionName: my-durable-function
      ImageConfig:
        Command: ["com.example.OrderProcessor::handleRequest"]
      DurableConfig:
        ExecutionTimeout: 3600        # Max execution time in seconds
        RetentionPeriodInDays: 7      # How long to keep execution history
      Policies:
        - Statement:
            - Effect: Allow
              Action:
                - lambda:CheckpointDurableExecutions
                - lambda:GetDurableExecutionState
              Resource: !Sub "arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:my-durable-function"
```

### Build and Deploy

```bash
# Build the project
mvn clean package

# Deploy with SAM
sam build
sam deploy --guided
```

See the [examples](./examples) directory for complete SAM templates and working examples.

## Examples

| Example | Description |
|---------|-------------|
| [SimpleStepExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/SimpleStepExample.java) | Basic sequential steps |
| [WaitExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/WaitExample.java) | Using wait operations |
| [RetryExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/RetryExample.java) | Configuring retry strategies |
| [GenericTypesExample](./examples/src/main/java/com/amazonaws/lambda/durable/examples/GenericTypesExample.java) | Working with generic types |

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
