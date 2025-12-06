# AWS Lambda Durable Execution SDK Examples

This module contains example applications demonstrating how to use the AWS Lambda Durable Execution SDK for Java.

## Running Examples Locally

The examples use the SDK's `LocalDurableTestRunner` to run without deploying to AWS Lambda:

```bash
# Run all example tests
mvn test

# Run specific test
mvn test -Dtest=SimpleStepExampleTest
```

## Examples

### SimpleStepExample

A basic example showing sequential step execution with the SDK.

**Handler:** `com.amazonaws.lambda.durable.examples.SimpleStepExample`

**Input:** `GreetingRequest`
```json
{
  "name": "Alice"
}
```

**Output:** `String`
```
HELLO, ALICE!
```

**Features Demonstrated:**
- Extending `DurableHandler<I, O>`
- Sequential step execution with `context.step()`
- Automatic checkpointing and replay

**Code:**
```java
public class SimpleStepExample extends DurableHandler<GreetingRequest, String> {
    @Override
    protected String handleRequest(GreetingRequest input, DurableContext context) {
        var greeting = context.step("create-greeting", String.class,
            () -> "Hello, " + input.getName());
        
        var uppercase = context.step("to-uppercase", String.class,
            () -> greeting.toUpperCase());
        
        var result = context.step("add-punctuation", String.class,
            () -> uppercase + "!");
        
        return result;
    }
}
```

### WaitExample

Demonstrates step execution with wait operations for time-based workflows.

**Handler:** `com.amazonaws.lambda.durable.examples.WaitExample`

**Input:** `GreetingRequest`
```json
{
  "name": "Bob"
}
```

**Output:** `String`
```
Started processing for Bob - continued after 10s - completed after 5s more
```

**Features Demonstrated:**
- Steps with `context.step()`
- Wait operations with `context.wait(Duration.ofSeconds(...))`
- Multi-invocation execution (function pauses during waits)
- No compute charges during wait periods

**Code:**
```java
public class WaitExample extends DurableHandler<GreetingRequest, String> {
    @Override
    protected String handleRequest(GreetingRequest input, DurableContext context) {
        var started = context.step("start-processing", String.class,
            () -> "Started processing for " + input.getName());
        
        context.wait(Duration.ofSeconds(10));  // Pause for 10 seconds
        
        var continued = context.step("continue-processing", String.class,
            () -> started + " - continued after 10s");
        
        context.wait(Duration.ofSeconds(5));   // Pause for 5 seconds
        
        var result = context.step("complete-processing", String.class,
            () -> continued + " - completed after 5s more");
        
        return result;
    }
}
```

## Building Deployable JARs

Build a Lambda deployment package:

```bash
mvn clean package
```

The deployable JAR will be in `target/aws-durable-execution-sdk-java-examples-1.0.0-SNAPSHOT.jar`.

## Deploying to AWS Lambda

See [DEPLOY.md](DEPLOY.md) for detailed deployment instructions.

### Quick Deploy with SAM

```bash
# Build
mvn clean package

# Deploy
sam deploy --guided

# Test
aws lambda invoke \
  --function-name simple-step-example \
  --payload '{"name":"Alice"}' \
  --cli-binary-format raw-in-base64-out \
  response.json
```

### Local Testing with SAM

```bash
# Start local Lambda endpoint
sam local start-lambda

# Or invoke directly
sam local invoke SimpleStepExampleFunction --event event.json
```

## Adding New Examples

1. Create a new handler class extending `DurableHandler<I, O>`
2. Implement the `handleRequest(I input, DurableContext context)` method
3. Create a test class using `LocalDurableTestRunner`
4. Document the example in this README

**Example Test:**
```java
@Test
void testExample() {
    var handler = new MyExample();
    var runner = new LocalDurableTestRunner<>(
        MyInput.class,
        handler::handleRequest
    );
    
    var result = runner.run(new MyInput("test"));
    
    assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    assertEquals("expected", result.getResult(String.class));
}
```

## Notes

- Examples are NOT published to Maven Central
- They depend on the local SDK snapshot
- Run `mvn install` from the project root to update the SDK dependency

