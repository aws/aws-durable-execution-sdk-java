# AWS Lambda Durable Execution SDK Examples

This module contains example applications demonstrating how to use the AWS Lambda Durable Execution SDK for Java.

# Running Examples with JUnit Tests

The examples demonstrate both local and cloud testing approaches using the SDK's testing utilities:

## Local Testing (Fast, No AWS Required)

Use `LocalDurableTestRunner` for fast, in-memory testing without deploying to AWS Lambda:

```bash
# Run all example tests
mvn test

# Run specific test
mvn test -Dtest=SimpleStepExampleTest
```

The local runner skips actual wait times and runs entirely in-memory, making tests fast and suitable for CI/CD pipelines.

## Cloud Testing (End-to-End)

Use `CloudDurableTestRunner` for end-to-end testing against deployed Lambda functions:

```java
var runner = CloudDurableTestRunner.create(
    "arn:aws:lambda:us-east-1:123456789012:function:MyFunction",
    MyInput.class,
    MyOutput.class);

var result = runner.run(new MyInput("test-data"));
```

This approach tests the actual deployed function, including all AWS integrations and durable execution behavior.

# Running Examples with AWS SAM locally

**Build the project:**
   ```bash
   mvn clean package
   ```

### Option 1: Local Lambda Endpoint

Start a local Lambda endpoint:

```bash
sam local start-lambda
```

In another terminal, invoke the function:

```bash
# Invoke SimpleStepExample
aws lambda invoke \
  --function-name SimpleStepExampleFunction \
  --endpoint-url http://127.0.0.1:3001 \
  --payload '{"name":"Alice"}' \
  --cli-binary-format raw-in-base64-out \
  response.json

# Invoke WaitExample
aws lambda invoke \
  --function-name WaitExampleFunction \
  --endpoint-url http://127.0.0.1:3001 \
  --payload '{"name":"Bob"}' \
  --cli-binary-format raw-in-base64-out \
  response.json
```

### Option 2: Direct Invocation

Invoke the function directly:

```bash
sam local invoke SimpleStepExampleFunction \
  --event event.json
```

Create `event.json`:
```json
{
  "name": "Alice"
}
```
