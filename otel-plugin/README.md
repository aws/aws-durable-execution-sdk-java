# AWS Durable Execution SDK - OpenTelemetry Plugin

> **Experimental Feature:** This plugin is currently experimental. Functionality may change without notice between releases. It is not recommended for production workloads at this time.

OpenTelemetry instrumentation plugin for the AWS Lambda Durable Execution SDK for Java. Emits distributed traces that correlate across multiple Lambda invocations of a single durable execution, producing deterministic span and trace IDs so that spans from different invocations are stitched into a single coherent trace.

## Features

- **Deterministic Trace IDs**: All invocations of the same durable execution share a single trace, derived from the X-Ray trace header or execution ARN
- **Span-per-Operation**: Each durable operation (step, wait, map, etc.) gets its own span with accurate timing
- **Attempt Spans**: Each user function execution (step attempt, child context run) gets a span, including retries
- **Log Correlation**: Injects `trace_id`, `span_id`, and `traceSampled` into SLF4J MDC for end-to-end observability
- **ADOT Java Agent Integration**: `new InvocationOtelPlugin()` uses the ADOT Java agent's global provider with no handler-side OpenTelemetry initialization
- **Lambda Layer Discovery**: `DURABLE_EXECUTION_PLUGINS` loads either OTel plugin from a JAR under a layer's `java/lib` directory

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-plugin-otel</artifactId>
    <version>${durable.sdk.version}</version>
</dependency>
```

For the no-arg constructor (`new InvocationOtelPlugin()`), no additional OpenTelemetry dependencies are needed — the ADOT Java agent layer provides them.

If you configure your own `SdkTracerProviderBuilder`, add the OpenTelemetry SDK and an exporter:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>1.64.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-logging</artifactId>
    <version>1.64.0</version>
</dependency>
```

## Quick Start using X-Ray/CloudWatch Tracing (ADOT Java Agent)

1. Add the ADOT Lambda Layer to your function
2. Enable X-Ray Active Tracing on the function
3. Configure environment variables
4. Load `InvocationOtelPlugin` dynamically or register it in your handler's `DurableConfig`
5. Grant X-Ray write permissions

### 1. ADOT Lambda Layer

This plugin uses the [AWS Distro for OpenTelemetry (ADOT) Lambda layer](https://aws-otel.github.io/docs/getting-started/lambda) for trace export. The `new InvocationOtelPlugin()` constructor uses the global provider initialized by the ADOT Java agent with deterministic span ID generation installed through the plugin's `AutoConfigurationCustomizerProvider` SPI.

The layer ARN follows the format:

```
arn:aws:lambda:<region>:615299751070:layer:AWSOpenTelemetryDistroJava:<version>
```

> **Note:** The layer is regional — the account ID and version vary by region. Find the current per-region ARN in the [ADOT Java instrumentation releases](https://github.com/aws-observability/aws-otel-java-instrumentation/releases/latest).

**CloudFormation / SAM:**

```yaml
MyFunction:
  Type: AWS::Serverless::Function
  Properties:
    Tracing: Active
    LoggingConfig:
      LogFormat: JSON
    Layers:
      - !Sub arn:aws:lambda:${AWS::Region}:615299751070:layer:AWSOpenTelemetryDistroJava:16
      - <otel-plugin-layer-arn>
    Environment:
      Variables:
        AWS_LAMBDA_EXEC_WRAPPER: /opt/otel-instrument
        OTEL_JAVAAGENT_EXTENSIONS: /opt/java/lib/aws-durable-execution-sdk-java-plugin-otel-<version>.jar
        DURABLE_EXECUTION_PLUGINS: otel-invocation
```

**AWS CLI:**

```bash
aws lambda update-function-configuration \
  --function-name your-function-name \
  --layers "arn:aws:lambda:<region>:615299751070:layer:AWSOpenTelemetryDistroJava:16" "<otel-plugin-layer-arn>" \
  --environment "Variables={AWS_LAMBDA_EXEC_WRAPPER=/opt/otel-instrument,OTEL_JAVAAGENT_EXTENSIONS=/opt/java/lib/aws-durable-execution-sdk-java-plugin-otel-<version>.jar,DURABLE_EXECUTION_PLUGINS=otel-invocation}"
```

Build the plugin layer ZIP with the OTel plugin JAR at `java/lib/aws-durable-execution-sdk-java-plugin-otel-<version>.jar`. Lambda adds JARs in this directory to the Java class path. Set `OTEL_JAVAAGENT_EXTENSIONS` to the deployed JAR so the ADOT Java agent also loads its `AutoConfigurationCustomizerProvider`, and set `DURABLE_EXECUTION_PLUGINS=otel-invocation` so the Durable Execution SDK loads its `InvocationOtelPluginProvider`.

### 2. AWS X-Ray Active Tracing

Enable active tracing on your Lambda function so the `_X_AMZN_TRACE_ID` environment variable is populated at invocation time. The plugin uses this header to derive deterministic trace IDs that remain consistent across all invocations of the same durable execution.

**AWS Console:** Lambda > Configuration > Monitoring and operations tools > Active tracing > Enable

**CloudFormation / SAM:**

```yaml
MyFunction:
  Type: AWS::Serverless::Function
  Properties:
    Tracing: Active
```

### 3. Plugin Registration

With the layer and `DURABLE_EXECUTION_PLUGINS=otel-invocation` configured above, no OTel plugin dependency or registration code is required in the function artifact. The function can use its existing `DurableConfig`.

The OTel plugin JAR exposes two dynamic provider names:

| Provider name | Plugin | Trace model |
|---------------|--------|-------------|
| `otel-invocation` | `InvocationOtelPlugin` | Invocation-rooted |
| `otel-execution` | `ExecutionOtelPlugin` | Workflow-rooted |

Set `DURABLE_EXECUTION_PLUGINS=otel-execution` to select the Workflow-rooted plugin instead.

Applications that prefer code-based configuration can continue to register the plugin explicitly:

```java
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.otel.InvocationOtelPlugin;

public class MyHandler extends DurableHandler<MyInput, MyOutput> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new InvocationOtelPlugin()).build();
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

The plugin creates spans at four levels:

```
Workflow (deterministic ID, exported once on terminal invocation)
Invocation
├── fetch-data
│   └── fetch-data attempt 1
├── cool-down
└── process
    └── process attempt 1
```

- **Workflow span** — one logical span per durable execution with a deterministic ID derived from the ARN. Exported only on the terminal invocation (SUCCEEDED/FAILED). Serves as a correlation anchor across invocations.
- **Invocation span** — one per Lambda invocation
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
| `trace_id` | W3C trace ID (32 hex chars) |
| `span_id` | Current span ID (16 hex chars) |
| `traceSampled` | Whether the trace is sampled (true/false) |

The `trace_id` is also injected at invocation start so handler-level logs (between steps) include it.

Configure your logging framework (e.g., Log4j2) to include MDC fields in the output. For example, using `JsonLayout`:

```xml
<Console name="Console" target="SYSTEM_OUT">
    <JsonLayout compact="true" eventEol="true" properties="true" />
</Console>
```

With Lambda's `LoggingConfig: JSON` (required for durable functions), CloudWatch parses the JSON and X-Ray correlates logs via `requestId` (injected by the core SDK's `DurableLogger`).

## Configuration

### Constructor Options

```java
// Default: ADOT Java agent global provider, X-Ray context extraction, MDC enabled
new InvocationOtelPlugin();

// Custom tracer provider pipeline
new InvocationOtelPlugin(tracerProviderBuilder);

// Custom context extractor, MDC enabled
new InvocationOtelPlugin(tracerProviderBuilder, contextExtractor);

// Full configuration
new InvocationOtelPlugin(tracerProviderBuilder, contextExtractor, enableMdc);
```

### InvocationOtelPlugin

```java
// Default: ADOT Java agent global provider, X-Ray context extraction, MDC enabled
new InvocationOtelPlugin();

// Custom tracer provider pipeline
new InvocationOtelPlugin(tracerProviderBuilder);

// Custom context extractor, MDC enabled
new InvocationOtelPlugin(tracerProviderBuilder, contextExtractor);

// Full configuration
new InvocationOtelPlugin(tracerProviderBuilder, contextExtractor, enableMdc);
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tracerProviderBuilder` | `SdkTracerProviderBuilder` with your exporter/processor configured | Not used by `new InvocationOtelPlugin()`; the default constructor uses the ADOT Java agent provider |
| `contextExtractor` | Extracts parent trace context from the Lambda environment | `XRayContextExtractor` |
| `enableMdc` | If true, injects `trace_id`/`span_id`/`traceSampled` into SLF4J MDC | `true` |

### ExecutionOtelPlugin

The `ExecutionOtelPlugin` renders the Workflow span as the trace root with operations as siblings of the invocation span. It supports the same constructor options:

```java
// Default: ADOT Java agent global provider, X-Ray context extraction, MDC enabled
new ExecutionOtelPlugin();

// Custom tracer provider pipeline
new ExecutionOtelPlugin(tracerProviderBuilder);

// Custom context extractor, MDC enabled
new ExecutionOtelPlugin(tracerProviderBuilder, contextExtractor);

// Full configuration
new ExecutionOtelPlugin(tracerProviderBuilder, contextExtractor, enableMdc, workflowSpanName);
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tracerProviderBuilder` | `SdkTracerProviderBuilder` with your exporter/processor configured | Not used by `new ExecutionOtelPlugin()`; the default constructor uses the ADOT Java agent provider |
| `contextExtractor` | Extracts parent trace context from the Lambda environment | `XRayContextExtractor` |
| `enableMdc` | If true, injects `trace_id`/`span_id`/`traceSampled` into SLF4J MDC | `true` |
| `workflowSpanName` | Name for the Workflow root span | `"Workflow"` |

## Known Limitations

### X-Ray Segments Timeline (ungrouped view)

The plugin's spans do not appear as nested subsegments of the Lambda platform segment in the ungrouped "Segments Timeline" view. This is because the ADOT collector's OTLP-to-X-Ray conversion cannot attach exported spans as subsegments of the Lambda service's native X-Ray segment (created outside the OTLP pipeline). Use the **"Group by nodes"** view to see the full span hierarchy.

### Workflow Span

The Workflow span appears as a separate root segment in the X-Ray trace because it uses `setNoParent()` with a deterministic span ID. This is expected — it serves as a correlation anchor across invocations.

## Verification

After deploying your function with the plugin configured:

1. **Invoke your durable function** — trigger at least one execution that includes multiple steps or a wait/resume cycle.

2. **Check CloudWatch console** — Navigate to CloudWatch > Traces. Enable "Group by nodes" to see:
   - A Workflow span covering the entire execution
   - An Invocation span per Lambda invocation
   - Child spans for each durable operation (named after your step names)
   - All invocations of the same execution grouped under one trace ID

3. **Check log correlation** — Verify that the Logs section at the bottom of the trace view shows both platform logs and application logs correlated with the trace.

### Troubleshooting

| Symptom | Likely Cause |
|---------|-------------|
| No traces appear | ADOT layer not added, or `AWS_LAMBDA_EXEC_WRAPPER` not set |
| Traces appear but are fragmented | X-Ray active tracing not enabled on the Lambda function |
| Missing spans for some operations | Sampling is configured below 1.0 |
| `_X_AMZN_TRACE_ID` not populated | X-Ray active tracing not enabled |
| Plugin spans missing but Lambda/runtime spans appear | Plugin jar not configured in `OTEL_JAVAAGENT_EXTENSIONS` |
| Logs not correlated | Ensure `LoggingConfig: JSON` is set and logging framework outputs MDC fields |

## Local Development

For local testing, use a logging exporter to print spans to stdout:

```java
import io.opentelemetry.exporter.logging.LoggingSpanExporter;

var otelPlugin = new InvocationOtelPlugin(
        SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create())));
```

## Requirements

- Java 17+
- AWS Durable Execution SDK for Java 2.0.0+
- OpenTelemetry SDK 1.64.0+ (only for custom TracerProvider path)
- ADOT Lambda Layer `AWSOpenTelemetryDistroJava` (for the no-arg constructor path)

## License

Apache-2.0
