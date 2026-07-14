# AWS Lambda Durable Execution SDK Examples

Example applications demonstrating the AWS Lambda Durable Execution SDK for Java.

## Prerequisites

- Java 17+
- Maven 3.8+
- AWS SAM CLI (for deployment)
- AWS credentials configured

## Local Testing

Run examples locally without deploying to AWS using `LocalDurableTestRunner`:

```bash
# Build and install the SDK to local Maven repo (required since SDK is not yet published)
mvn clean install -DskipTests   # from project root

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
python3 generate-template.py
sam build
sam deploy --guided
```

On first deploy, SAM will prompt for stack name and region. Subsequent deploys use saved config:

```bash
sam deploy
```

The SAM template configures:
- `DurableConfig` with `ExecutionTimeout` and `RetentionPeriodInDays`
- CloudWatch log groups for Lambda functions with 7 days of retention
- IAM permissions for `lambda:CheckpointDurableExecutions` and `lambda:GetDurableExecutionState`
- ADOT tracing examples with `AWS_LAMBDA_EXEC_WRAPPER` and `OTEL_JAVAAGENT_EXTENSIONS` so the OTel plugin SPI is loaded by the Java agent

`template.yaml` is generated from the Java example handlers and is intentionally not checked in. Re-run `python3 generate-template.py` after adding or removing a deployable example handler.

The examples package copies the OTel plugin jar into `lib/` so the ADOT Java agent can load it as an extension from the generated `OTEL_JAVAAGENT_EXTENSIONS` path.

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
| [SimpleStepExample](src/main/java/software/amazon/lambda/durable/examples/step/SimpleStepExample.java) | Basic sequential steps |
| [WaitExample](src/main/java/software/amazon/lambda/durable/examples/wait/WaitExample.java) | Suspend execution with `wait()` |
| [RetryExample](src/main/java/software/amazon/lambda/durable/examples/step/RetryExample.java) | Configuring retry strategies |
| [ErrorHandlingExample](src/main/java/software/amazon/lambda/durable/examples/general/ErrorHandlingExample.java) | Handling `StepFailedException` and `StepInterruptedException` |
| [GenericTypesExample](src/main/java/software/amazon/lambda/durable/examples/general/GenericTypesExample.java) | Working with `List<T>` and `Map<K,V>` |
| [CustomConfigExample](src/main/java/software/amazon/lambda/durable/examples/general/CustomConfigExample.java) | Custom Lambda client and SerDes |
| [WaitAtLeastExample](src/main/java/software/amazon/lambda/durable/examples/wait/WaitAtLeastExample.java) | Concurrent `stepAsync()` with `wait()` |
| [WaitAsyncExample](src/main/java/software/amazon/lambda/durable/examples/wait/WaitAsyncExample.java) | Non-blocking `waitAsync()` with concurrent step |
| [RetryInProcessExample](src/main/java/software/amazon/lambda/durable/examples/step/RetryInProcessExample.java) | In-process retry with concurrent operations |
| [WaitAtLeastInProcessExample](src/main/java/software/amazon/lambda/durable/examples/wait/WaitAtLeastInProcessExample.java) | Wait completes before async step (no suspension) |
| [ManyAsyncStepsExample](src/main/java/software/amazon/lambda/durable/examples/step/ManyAsyncStepsExample.java) | Performance test with 500 concurrent async steps |
| [SimpleMapExample](src/main/java/software/amazon/lambda/durable/examples/map/SimpleMapExample.java) | Concurrent map over a collection with durable steps |
| [CustomShouldCompleteMapExample](src/main/java/software/amazon/lambda/durable/examples/map/CustomShouldCompleteMapExample.java) | Custom map completion with `shouldComplete` decisions |
| [WaitForConditionExample](src/main/java/software/amazon/lambda/durable/examples/wait/WaitForConditionExample.java) | Poll a condition until met with `waitForCondition()` |
| [OtelExample](src/main/java/software/amazon/lambda/durable/examples/general/OtelExample.java) | OpenTelemetry instrumentation with logging span export |
| [OtelXRayDefaultConstructorExample](src/main/java/software/amazon/lambda/durable/examples/otel/OtelXRayDefaultConstructorExample.java) | Export spans to X-Ray with `new OtelPlugin()` and no handler-side OpenTelemetry initialization |
| [OtelXRayStepExample](src/main/java/software/amazon/lambda/durable/examples/otel/OtelXRayStepExample.java) | Export step spans to X-Ray through the ADOT Lambda Layer |
| [OtelXRayWaitExample](src/main/java/software/amazon/lambda/durable/examples/otel/OtelXRayWaitExample.java) | Trace a step-wait-step workflow across Lambda invocations |
| [OtelXRayMapExample](src/main/java/software/amazon/lambda/durable/examples/otel/OtelXRayMapExample.java) | Trace concurrent map operations and item steps in X-Ray |
| [OtelXRayParallelExample](src/main/java/software/amazon/lambda/durable/examples/otel/OtelXRayParallelExample.java) | Trace parallel branches and branch steps in X-Ray |
| [OtelXRayNestedContextExample](src/main/java/software/amazon/lambda/durable/examples/otel/OtelXRayNestedContextExample.java) | Trace nested child contexts and inner steps in X-Ray |

## Cleanup

```bash
sam delete
```

If an existing e2e test stack has Lambda-created log groups that predate the managed log group resources, clean them up before redeploying:

```bash
../.github/scripts/cleanup_e2e_unmanaged_log_groups.sh --stack-name Java17-JavaSDKCloudBasedIntegrationTestStack
../.github/scripts/cleanup_e2e_unmanaged_log_groups.sh --execute --stack-name Java17-JavaSDKCloudBasedIntegrationTestStack
```
