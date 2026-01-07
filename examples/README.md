# AWS Lambda Durable Execution SDK Examples

Example applications demonstrating the AWS Lambda Durable Execution SDK for Java.

## Prerequisites

- Java 17+
- Maven 3.8+
- AWS SAM CLI (for deployment)
- Docker (for SAM build)
- AWS credentials configured

## Local Testing

Run examples locally without AWS using `LocalDurableTestRunner`:

```bash
# First, build and install the SDK to local Maven repo (from project root)
mvn clean install -DskipTests

cd examples

# Run all tests
mvn test

# Run specific test
mvn test -Dtest=SimpleStepExampleTest
```

The local runner executes in-memory and skips wait durations—ideal for fast iteration and CI/CD.

## Deploy to AWS

```bash
cd examples
mvn clean package
sam build
sam deploy --guided
```

On first deploy, SAM will prompt for stack name and region. Subsequent deploys use saved config:

```bash
sam deploy
```

The SAM template configures:
- `DurableConfig` with `ExecutionTimeout` and `RetentionPeriodInDays`
- IAM permissions for `lambda:CheckpointDurableExecutions` and `lambda:GetDurableExecutionState`

## Invoke Deployed Functions

```bash
sam remote invoke SimpleStepExampleFunction \
  --event '{"name":"World"}' \
  --stack-name durable-sdk-examples
```

## Cloud Integration Tests

Run tests against deployed functions using `CloudDurableTestRunner`:

```bash
cd examples
mvn test -Dtest=CloudBasedIntegrationTest -Dtest.cloud.enabled=true
```

The tests auto-detect your AWS account and region from credentials. Override if needed:

```bash
mvn test -Dtest=CloudBasedIntegrationTest \
  -Dtest.cloud.enabled=true \
  -Dtest.aws.account=123456789012 \
  -Dtest.aws.region=us-east-1
```

## Examples

| Example | Description |
|---------|-------------|
| [SimpleStepExample](src/main/java/com/amazonaws/lambda/durable/examples/SimpleStepExample.java) | Basic sequential steps |
| [WaitExample](src/main/java/com/amazonaws/lambda/durable/examples/WaitExample.java) | Suspend execution with `wait()` |
| [RetryExample](src/main/java/com/amazonaws/lambda/durable/examples/RetryExample.java) | Configuring retry strategies |
| [GenericTypesExample](src/main/java/com/amazonaws/lambda/durable/examples/GenericTypesExample.java) | Working with `List<T>` and `Map<K,V>` |
| [CustomConfigExample](src/main/java/com/amazonaws/lambda/durable/examples/CustomConfigExample.java) | Custom Lambda client and SerDes |
| [WaitAtLeastExample](src/main/java/com/amazonaws/lambda/durable/examples/WaitAtLeastExample.java) | Concurrent `stepAsync()` with `wait()` |
| [RetryInProcessExample](src/main/java/com/amazonaws/lambda/durable/examples/RetryInProcessExample.java) | In-process retry with concurrent operations |
| [WaitAtLeastInProcessExample](src/main/java/com/amazonaws/lambda/durable/examples/WaitAtLeastInProcessExample.java) | Wait completes before async step (no suspension) |

## Cleanup

```bash
sam delete
```
