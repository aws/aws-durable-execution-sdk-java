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

### Inspecting Results with a Custom SerDes

When a step uses a per-operation serializer, pass that serializer to the result
accessor. The runner supplies the durable execution ARN and the step's
`operation/<operation-id>/result` entity ID automatically:

```java
var document = result.getOperation("load-document")
        .getStepResult(String.class, files);

var records = runner.getOperation("load-records")
        .getStepResult(new TypeToken<List<String>>() {}, files);
```

Here, `files` is the `FileSystemSerDes` configuration used by the corresponding
step. Any custom `SerDes` can be supplied, including implementations of the
original context-free interface. Both `Class<T>` and `TypeToken<T>` overloads
support this explicit override. Missing step results return null.

The override applies only to that read. `getStepResult(type)` still uses the
runner's configured global serializer, and handler input/output serialization
is unchanged. The test runner does not discover a step's custom serializer from
its checkpoint or automatically follow file pointers. To inspect the stored
checkpoint string, use `getStepDetails().result()`.

This API works for local result snapshots, `LocalDurableTestRunner.getOperation`,
cloud results, and `AsyncExecution` snapshots. Each snapshot retains the execution
identity it was created with, including after replay or when a cloud runner is
reused for another execution.

Local filesystem tests can use a JUnit `@TempDir`. Cloud tests that decode file
references need access to the same filesystem at the absolute path stored by the
function; the runner does not download payload files from Lambda. Collecting
results and inspecting raw pointers remains possible without that mount. An
explicit filesystem read fails with `SerDesException` if its file is unavailable.

Manually constructed `TestOperation` instances can supply the execution ARN through
`new TestOperation(operation, events, serDes, executionArn)`. The existing
constructors remain supported and use context-free deserialization when no ARN is
available.

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


### Conformance Tests

Conformance tests verify cross-SDK behavioral parity and live in a separate repository: [aws-durable-execution-conformance-tests](https://github.com/aws/aws-durable-execution-conformance-tests). Contributors do not need to add conformance tests. If you believe a change warrants one, mention it in your PR description or [open an issue](https://github.com/aws/aws-durable-execution-conformance-tests/issues/new?template=new_requirement.yml) in that repository.
