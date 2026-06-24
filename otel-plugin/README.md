# AWS Durable Execution SDK - OpenTelemetry Plugin

> **Experimental Feature:** This plugin is currently experimental. Functionality may change without notice between releases. It is not recommended for production workloads at this time.

OpenTelemetry instrumentation plugin for the AWS Lambda Durable Execution SDK for Java. Emits distributed traces that correlate across multiple Lambda invocations of a single durable execution, producing deterministic span and trace IDs so that spans from different invocations are stitched into a single coherent trace.

## Features

- **Deterministic Trace IDs**: All invocations of the same durable execution share a single trace, derived from the X-Ray trace header or execution ARN
- **Span-per-Operation**: Each durable operation (step, wait, map, etc.) gets its own span with accurate timing
- **Attempt Spans**: Each user function execution (step attempt, child context run) gets a span, including retries
- **Log Correlation**: Injects `trace_id` and `span_id` into SLF4J MDC for end-to-end observability
- **Self-Contained Setup**: No manual TracerProvider configuration required beyond the exporter

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-plugin-otel</artifactId>
    <version>0.1.0</version>
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

1. Add the ADOT Lambda Layer to your function and set `AWS_LAMBDA_EXEC_WRAPPER=/opt/otel-handler`
2. Enable X-Ray Active Tracing on the function
3. Register `OpenTelemetryDurablePlugin` in your handler's `DurableConfig`
4. Grant X-Ray write permissions

### 1. ADOT Lambda Layer

This plugin requires the [AWS Distro for OpenTelemetry (ADOT) Lambda layer](https://aws-otel.github.io/docs/getting-started/lambda) to export traces from your Lambda function.

The layer ARN follows the format:

```
arn:aws:lambda:<region>:<account-id>:layer:AWSOpenTelemetryDistroJava:<version>
```

The account ID varies by region. Refer to the [ADOT Lambda Layer ARNs](https://aws-otel.github.io/docs/getting-started/lambda#aws-lambda-layer-for-opentelemetry-arns) page for region-specific ARNs, account IDs, and the latest version number.

**AWS CLI:**

```bash
aws lambda update-function-configuration \
  --function-name your-function-name \
  --layers "arn:aws:lambda:<region>:<account-id>:layer:AWSOpenTelemetryDistroJava:<version>"
```

You must also set the `AWS_LAMBDA_EXEC_WRAPPER` environment variable:

```bash
aws lambda update-function-configuration \
  --function-name your-function-name \
  --environment "Variables={AWS_LAMBDA_EXEC_WRAPPER=/opt/otel-handler}"
```

**CloudFormation / SAM:**

```yaml
MyFunction:
  Type: AWS::Serverless::Function
  Properties:
    Layers:
      - !Sub arn:aws:lambda:${AWS::Region}:<account-id>:layer:AWSOpenTelemetryDistroJava:<version>
    Environment:
      Variables:
        AWS_LAMBDA_EXEC_WRAPPER: /opt/otel-handler
```

**CDK (Java):**

```java
import software.amazon.awscdk.services.lambda.*;

var adotLayer = LayerVersion.fromLayerVersionArn(this, "AdotLayer",
        String.format("arn:aws:lambda:%s:<account-id>:layer:AWSOpenTelemetryDistroJava:<version>",
                this.getRegion()));

Function.Builder.create(this, "MyFunction")
        .runtime(Runtime.JAVA_17)
        .handler("com.example.MyHandler::handleRequest")
        .code(Code.fromAsset("target/my-function.jar"))
        .layers(List.of(adotLayer))
        .environment(Map.of("AWS_LAMBDA_EXEC_WRAPPER", "/opt/otel-handler"))
        .build();
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

**CDK (Java):**

```java
Function.Builder.create(this, "MyFunction")
        .tracing(Tracing.ACTIVE)
        .build();
```

### 3. In Your Lambda Handler

```java
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.otel.OpenTelemetryDurablePlugin;

public class MyHandler extends DurableHandler<MyInput, MyOutput> {

    @Override
    protected DurableConfig createConfiguration() {
        // OTLP exporter sends spans to the ADOT collector (localhost:4317 by default)
        var otlpExporter = OtlpGrpcSpanExporter.getDefault();

        var otelPlugin = new OpenTelemetryDurablePlugin(
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

That's it. The plugin handles TracerProvider setup, deterministic ID generation, and span lifecycle internally.

### 4. Grant Permissions

The function's execution role needs the `AWSXRayDaemonWriteAccess` managed policy (or equivalent permissions) to write traces to X-Ray.

## Trace Structure

The plugin creates spans at three levels:

```
durable.invocation
├── durable.step:fetch-data
│   └── durable.step:fetch-data [attempt 1]
├── durable.wait:cool-down
└── durable.step:process
    └── durable.step:process [attempt 1]
```

- **Invocation span** — one per Lambda invocation, covers the entire invocation lifecycle
- **Operation span** — one per durable operation, named after your step/wait names
- **Attempt span** — one per user function execution (retries produce additional attempt spans)

## Configuration

### Constructor Options

```java
// Default: X-Ray context extraction, MDC enabled
new OpenTelemetryDurablePlugin(tracerProviderBuilder);

// Custom context extractor, MDC enabled
new OpenTelemetryDurablePlugin(tracerProviderBuilder, contextExtractor);

// Full configuration
new OpenTelemetryDurablePlugin(tracerProviderBuilder, contextExtractor, enableMdc);
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tracerProviderBuilder` | `SdkTracerProviderBuilder` with your exporter/processor configured | Required |
| `contextExtractor` | Extracts parent trace context from the Lambda environment | `XRayContextExtractor` |
| `enableMdc` | If true, injects `trace_id`/`span_id` into SLF4J MDC | `true` |

### Environment Variables for ADOT Layer

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Endpoint for the OTLP exporter | Set by ADOT layer |
| `AWS_LAMBDA_EXEC_WRAPPER` | Set to `/opt/otel-handler` for ADOT layer instrumentation | — |
| `OTEL_TRACES_SAMPLER` | Sampler to use (e.g., `traceidratio` for ratio-based sampling) | `always_on` |
| `OTEL_TRACES_SAMPLER_ARG` | Argument for the sampler (e.g., `0.3` to sample 30%) | — |

## Verification

After deploying your function with the plugin configured:

1. **Invoke your durable function** — trigger at least one execution that includes multiple steps or a wait/resume cycle.

2. **Check CloudWatch console** — Navigate to CloudWatch > Traces. You should see a trace with:
   - An invocation span per Lambda invocation
   - Child spans for each durable operation (named after your step names)
   - All invocations of the same execution grouped under one trace ID

3. **Check log correlation** — Verify that your logs include `trace_id` and `span_id` fields matching the spans in the trace view.

4. **Confirm sampling** — If you set `OTEL_TRACES_SAMPLER=traceidratio` with an arg less than 1.0, verify that only the expected proportion of traces appear.

### Troubleshooting

| Symptom | Likely Cause |
|---------|-------------|
| No traces appear | ADOT layer not configured, or `AWS_LAMBDA_EXEC_WRAPPER` not set |
| Traces appear but are fragmented | X-Ray active tracing not enabled on the Lambda function |
| Missing spans for some operations | `OTEL_TRACES_SAMPLER_ARG` set below 1.0 |
| `_X_AMZN_TRACE_ID` not populated | X-Ray active tracing not enabled |

## Local Development

For local testing, use a logging exporter to print spans to stdout:

```java
import io.opentelemetry.exporter.logging.LoggingSpanExporter;

var otelPlugin = new OpenTelemetryDurablePlugin(
        SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create())));
```

## API Reference

### `OpenTelemetryDurablePlugin`

The main plugin class. Implements `DurableExecutionPlugin` from the core SDK.

```java
new OpenTelemetryDurablePlugin(SdkTracerProviderBuilder tracerProviderBuilder)
new OpenTelemetryDurablePlugin(SdkTracerProviderBuilder tracerProviderBuilder, ContextExtractor contextExtractor)
new OpenTelemetryDurablePlugin(SdkTracerProviderBuilder tracerProviderBuilder, ContextExtractor contextExtractor, boolean enableMdc)
```

### `XRayContextExtractor`

Default context extractor. Reads the `_X_AMZN_TRACE_ID` environment variable to derive trace context.

### `ContextExtractor`

Interface for custom context extractor implementations.

## Requirements

- Java 17+
- AWS Durable Execution SDK for Java 1.2.1+
- OpenTelemetry SDK 1.20.0+

## License

Apache-2.0
