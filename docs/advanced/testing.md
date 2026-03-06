## Testing

The SDK includes testing utilities for both local development and cloud-based integration testing.

### Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
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
    var result = ctx.step("process", String.class, stepCtx -> "done");
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