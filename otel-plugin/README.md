# AWS Durable Execution SDK - OpenTelemetry Plugin

> **Experimental Feature:** This plugin is currently experimental. Functionality may change without notice between releases. It is not recommended for production workloads at this time.

OpenTelemetry instrumentation plugin for the AWS Lambda Durable Execution SDK for Java. Emits distributed traces that correlate across multiple Lambda invocations of a single durable execution, producing deterministic span and trace IDs so that spans from different invocations are stitched into a single coherent trace.

## Features

- **Deterministic Trace IDs**: All invocations of the same durable execution share a single trace, derived from the X-Ray trace header or execution ARN
- **Span-per-Operation**: Each durable operation (step, wait, map, etc.) gets its own span with accurate timing
- **Attempt Spans**: Each user function execution (step attempt, child context run) gets a span, including retries
- **Log Correlation**: Injects `traceId`, `spanId`, and `traceSampled` into SLF4J MDC for end-to-end observability
- **Self-Contained Setup**: No manual TracerProvider configuration required beyond the exporter

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-plugin-otel</artifactId>
    <version>${durable.sdk.version}</version>
</dependency>
```

You also need the OpenTelemetry SDK and an exporter:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>1.63.0</version>
</dependency>
```

## Quick Start using X-Ray/CloudWatch Tracing

1. Add the ADOT Lambda Layer to your function
2. Enable X-Ray Active Tracing on the function
3. Register `OtelPlugin` in your handler's `DurableConfig`
4. Grant X-Ray write permissions

### 1. ADOT Lambda Layer

This plugin uses the [AWS Distro for OpenTelemetry (ADOT) Lambda layer](https://aws-otel.github.io/docs/getting-started/lambda) for trace export. Configure an OpenTelemetry SDK provider with an OTLP exporter; `OtelPlugin()` copies that provider's export pipeline from `GlobalOpenTelemetry`.

The layer ARN follows the format:

```
arn:aws:lambda:<region>:615299751070:layer:AWSOpenTelemetryDistroJava:15
```

**CloudFormation / SAM:**

```yaml
MyFunction:
  Type: AWS::Serverless::Function
  Properties:
    Tracing: Active
    Layers:
      - !Sub arn:aws:lambda:${AWS::Region}:615299751070:layer:AWSOpenTelemetryDistroJava:15
```

**AWS CLI:**

```bash
aws lambda update-function-configuration \
  --function-name your-function-name \
  --layers "arn:aws:lambda:<region>:615299751070:layer:AWSOpenTelemetryDistroJava:15"
```

### 2. AWS X-Ray Active Tracing

Enable active tracing on your Lambda function so the `_X_AMZN_TRACE_ID` environment variable is populated at invocation time. The plugin uses this header to derive deterministic trace IDs that remain consistent across all invocations of the same durable execution.

**AWS Console:** Lambda > Configuration > Monitoring and operations tools > Active tracing > Enable

**AWS CLI:**

```bash
aws lambda update-function-configuration \
  --function-name your-function-name \
  --tracing-config Mode=Active
```

**CloudFormation / SAM:**

```yaml
MyFunction:
  Type: AWS::Lambda::Function
  Properties:
    TracingConfig:
      Mode: Active
```

### 3. In Your Lambda Handler

```java
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.otel.OtelPlugin;

public class MyHandler extends DurableHandler<MyInput, MyOutput> {

    @Override
    protected DurableConfig createConfiguration() {
        var otlpExporter = OtlpGrpcSpanExporter.getDefault();

        var otelPlugin = new OtelPlugin(
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(otlpExporter)));

        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    @Override
    public MyOutput handleRequest(MyInput input, DurableContext context) {
        var result = context.step("fetch-data", String.class, stepCtx -> {
            return fetchData(input.getId());
        });

        context.wait("cool-down", Duration.ofSeconds(5));

        context.step("process", Void.class, stepCtx -> {
            process(result);
            return null;
        });

        return new MyOutput(result);
    }
}
```

### 4. Grant Permissions

The function's execution role needs the `AWSXRayDaemonWriteAccess` managed policy (or equivalent permissions) to write traces to X-Ray.

## Trace Structure

The plugin creates spans at three levels:

```
invocation
├── fetch-data
│   └── fetch-data attempt 1
├── cool-down
└── process
    └── process attempt 1
```

- **Invocation span** (`SpanKind.SERVER`) — one per Lambda invocation, creates a distinct X-Ray service node
- **Operation span** — one per durable operation, named after your step/wait names
- **Attempt span** — one per user function execution (retries produce additional attempt spans)

## Span Attributes

### Invocation Span

| Attribute | Description |
|-----------|-------------|
| `durable.execution.arn` | The durable execution ARN |
| `durable.invocation.status` | SUCCEEDED, FAILED, PENDING, or RETRYING |
| `durable.invocation.first` | Whether this is the first invocation of the execution |
| `faas.invocation_id` | Lambda request ID |

### Operation Span

| Attribute | Description |
|-----------|-------------|
| `durable.execution.arn` | The durable execution ARN |
| `durable.operation.id` | Unique operation ID |
| `durable.operation.type` | STEP, WAIT, CONTEXT, CHAINED_INVOKE, CALLBACK |
| `durable.operation.name` | Human-readable name (if provided) |
| `durable.operation.subtype` | Map, Parallel, WaitForCondition, etc. |
| `durable.operation.status` | Backend status: SUCCEEDED, FAILED, PENDING, TIMED_OUT, etc. |

### Attempt Span (not emitted for CONTEXT operations)

| Attribute | Description |
|-----------|-------------|
| `durable.execution.arn` | The durable execution ARN |
| `durable.operation.id` | Parent operation ID |
| `durable.operation.type` | Parent operation type |
| `durable.operation.name` | Parent operation name |
| `durable.attempt.number` | 1-based attempt number |
| `durable.attempt.outcome` | SUCCEEDED or FAILED |

## Log Correlation (MDC)

When `enableMdc` is true (default), the plugin injects these fields into SLF4J MDC during user function execution:

| MDC Key | Description |
|---------|-------------|
| `traceId` | W3C trace ID (32 hex chars) |
| `spanId` | Current span ID (16 hex chars) |
| `traceSampled` | Whether the trace is sampled (true/false) |

These appear automatically in structured log output (Log4j2 JSON, Logback JSON) for log-trace correlation.

## Configuration

### Constructor Options

```java
// Default: X-Ray context extraction, MDC enabled
new OtelPlugin(tracerProviderBuilder);

// Custom context extractor, MDC enabled
new OtelPlugin(tracerProviderBuilder, contextExtractor);

// Full configuration
new OtelPlugin(tracerProviderBuilder, contextExtractor, enableMdc);
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tracerProviderBuilder` | `SdkTracerProviderBuilder` with your exporter/processor configured | Required |
| `contextExtractor` | Extracts parent trace context from the Lambda environment | `XRayContextExtractor` |
| `enableMdc` | If true, injects `traceId`/`spanId`/`traceSampled` into SLF4J MDC | `true` |

## Verification

After deploying your function with the plugin configured:

1. **Invoke your durable function** — trigger at least one execution that includes multiple steps or a wait/resume cycle.

2. **Check CloudWatch console** — Navigate to CloudWatch > Traces. You should see a trace with:
   - An invocation span per Lambda invocation
   - Child spans for each durable operation (named after your step names)
   - All invocations of the same execution grouped under one trace ID

3. **Check log correlation** — Verify that your logs include `traceId` and `spanId` fields matching the spans in the trace view.

### Troubleshooting

| Symptom | Likely Cause |
|---------|-------------|
| No traces appear | ADOT layer not added to the function |
| Traces appear but are fragmented | X-Ray active tracing not enabled on the Lambda function |
| Missing spans for some operations | Sampling is configured below 1.0 |
| `_X_AMZN_TRACE_ID` not populated | X-Ray active tracing not enabled |
| Plugin spans missing but Lambda/runtime spans appear | `GlobalOpenTelemetry` was not initialized with an SDK provider and OTLP exporter, or the exporter cannot reach the ADOT collector |

> **Note on ADOT wrapper:** Do not set `AWS_LAMBDA_EXEC_WRAPPER` for these examples. The plugin owns a provider with a deterministic durable ID generator and exports through the SDK provider configured by your application.

## Local Development

For local testing, use a logging exporter to print spans to stdout:

```java
import io.opentelemetry.exporter.logging.LoggingSpanExporter;

var otelPlugin = new OtelPlugin(
        SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create())));
```

## Requirements

- Java 17+
- AWS Durable Execution SDK for Java 2.0.0+
- OpenTelemetry SDK 1.30.0+

## License

Apache-2.0
