# AWS Durable Execution SDK - OpenTelemetry Plugin

> **Experimental Feature:** This plugin is currently experimental. Functionality may change without notice between releases. It is not recommended for production workloads at this time.

OpenTelemetry instrumentation plugin for the AWS Lambda Durable Execution SDK for Java. Emits distributed traces that correlate across multiple Lambda invocations of a single durable execution, producing deterministic span and trace IDs so that spans from different invocations are stitched into a single coherent trace.

## Features

- **Deterministic Trace IDs**: All invocations of the same durable execution share a single trace, derived from the X-Ray trace header or execution ARN
- **Span-per-Operation**: Each durable operation (step, wait, map, etc.) gets its own span with accurate timing
- **Attempt Spans**: Each user function execution (step attempt, child context run) gets a span, including retries
- **Log Correlation**: Injects `traceId`, `spanId`, and `traceSampled` into SLF4J MDC for end-to-end observability
- **ADOT Java Agent Setup**: `new OtelPlugin()` uses the ADOT Java agent global OpenTelemetry provider without handler-side initialization

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-plugin-otel</artifactId>
    <version>${durable.sdk.version}</version>
</dependency>
```

If you configure your own `SdkTracerProviderBuilder`, add the OpenTelemetry SDK and an exporter:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-logging</artifactId>
    <version>1.63.0</version>
</dependency>
```

For `new OtelPlugin()` on Lambda, use the ADOT Java agent setup below instead of initializing an exporter in your
handler.

## Quick Start using X-Ray/CloudWatch Tracing

1. Add the ADOT Lambda Layer to your function
2. Enable X-Ray Active Tracing on the function
3. Register `OtelPlugin` in your handler's `DurableConfig`
4. Grant X-Ray write permissions

### 1. ADOT Lambda Layer

This plugin uses the [AWS Distro for OpenTelemetry (ADOT) Lambda layer](https://aws-otel.github.io/docs/getting-started/lambda) for trace export. `OtelPlugin()` uses the global provider initialized by the ADOT Java agent and requires deterministic span ID generation to be installed through the OpenTelemetry `AutoConfigurationCustomizerProvider` SPI.

The layer ARN follows the format:

```
arn:aws:lambda:<region>:615299751070:layer:AWSOpenTelemetryDistroJava:16
```

> **Note:** This layer is regional — the account ID and version vary by region. Use the ARN for your deployment region; find the current per-region ARN in the [ADOT Java instrumentation releases](https://github.com/aws-observability/aws-otel-java-instrumentation/releases/latest).

**CloudFormation / SAM:**

```yaml
MyFunction:
  Type: AWS::Serverless::Function
  Properties:
    Tracing: Active
    Layers:
      - !Sub arn:aws:lambda:${AWS::Region}:615299751070:layer:AWSOpenTelemetryDistroJava:16
    Environment:
      Variables:
        AWS_LAMBDA_EXEC_WRAPPER: /opt/otel-instrument
        OTEL_JAVAAGENT_EXTENSIONS: /var/task/lib/aws-durable-execution-sdk-java-plugin-otel-<version>.jar
```

**AWS CLI:**

```bash
aws lambda update-function-configuration \
  --function-name your-function-name \
  --layers "arn:aws:lambda:<region>:615299751070:layer:AWSOpenTelemetryDistroJava:16" \
  --environment "Variables={AWS_LAMBDA_EXEC_WRAPPER=/opt/otel-instrument,OTEL_JAVAAGENT_EXTENSIONS=/var/task/lib/aws-durable-execution-sdk-java-plugin-otel-<version>.jar}"
```

Set `OTEL_JAVAAGENT_EXTENSIONS` to the deployed OTel plugin jar that contains this plugin's `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider` entry. Prefer the standalone plugin dependency jar under `/var/task/lib` so the Java agent loads the extension against its own OpenTelemetry SPI classes.

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
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.otel.OtelPlugin;

public class MyHandler extends DurableHandler<MyInput, MyOutput> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new OtelPlugin()).build();
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
// Default: X-Ray context extraction, MDC enabled, ADOT Java agent global provider
new OtelPlugin();

// Custom tracer provider pipeline
new OtelPlugin(tracerProviderBuilder);

// Custom context extractor, MDC enabled
new OtelPlugin(tracerProviderBuilder, contextExtractor);

// Full configuration
new OtelPlugin(tracerProviderBuilder, contextExtractor, enableMdc);
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tracerProviderBuilder` | `SdkTracerProviderBuilder` with your exporter/processor configured | Not used by `new OtelPlugin()`; the default constructor uses the ADOT Java agent provider configured by the plugin SPI |
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
| Plugin spans missing but Lambda/runtime spans appear | The ADOT wrapper did not initialize `GlobalOpenTelemetry`, or the plugin jar was not configured in `OTEL_JAVAAGENT_EXTENSIONS` |

> **Note on ADOT wrapper:** Use `AWS_LAMBDA_EXEC_WRAPPER=/opt/otel-instrument` with the `AWSOpenTelemetryDistroJava` layer. The older `/opt/otel-handler` path is not valid for this layer and can fail before the Java handler starts.

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
