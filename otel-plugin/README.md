# AWS Durable Execution SDK - OpenTelemetry Plugin

OpenTelemetry instrumentation plugin for the AWS Lambda Durable Execution SDK for Java. Anchors every durable execution on one trace so the Workflow span and its per-invocation spans stay correlated, joining the propagated backend trace when one is present.

## Features

- **Backend-parented execution trace**: The Workflow span parents onto the execution ancestor resolved at invocation start — a propagated remote context, or a synthetic execution root — for one trace ID that is stable across all invocations, plus a stable span ID derived from the ARN
- **Ambient Invocation Traces**: Invocation spans inherit the active Lambda/X-Ray context, or join the execution ancestor so they stay on the execution trace
- **Scoped ID Generation**: Unrelated instrumentation scopes retain their provider's normal root trace ID generation
- **Span-per-Operation**: Each durable operation (step, wait, map, etc.) gets its own span with accurate timing
- **Attempt Spans**: Each user function execution (step attempt, child context run) gets a span, including retries
- **Log Correlation**: Injects `trace_id`, `span_id`, and `traceSampled` into SLF4J MDC for end-to-end observability
- **ADOT Java Agent Integration**: `new InvocationOtelPlugin()` late-binds the ADOT Java agent's global provider with no handler-side OpenTelemetry initialization
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

This plugin uses the [AWS Distro for OpenTelemetry (ADOT) Lambda layer](https://aws-otel.github.io/docs/getting-started/lambda) for trace export. The `new InvocationOtelPlugin()` constructor resolves the global provider initialized by the ADOT Java agent at invocation start, with deterministic span ID generation installed through the plugin's `AutoConfigurationCustomizerProvider` SPI. If the provider is not ready, the plugin emits no telemetry for that invocation and retries provider resolution on the next invocation.

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

Enable active tracing on your Lambda function so the `_X_AMZN_TRACE_ID` environment variable is populated at invocation time. The plugin uses this header both to parent Invocation spans to the ambient Lambda/X-Ray trace and to anchor the execution trace on the propagated context when it carries a complete parent and an explicit sampling decision.

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

The whole execution shares one trace, anchored at the execution ancestor resolved at invocation start. When the backend propagates a valid remote server span (`Root` and `Parent`), that span is the ancestor and the Workflow and Invocation spans nest under it, alongside the ambient Lambda spans on the same trace:

```
Remote backend server span (Root / Parent)
├── Workflow (stable span ID, exported once)
├── Ambient Lambda span 1
│   └── Invocation 1
├── Ambient Lambda span 2
│   └── Invocation 2
└── Invocation N          (direct child when no same-trace ambient span exists)
```

When no valid remote parent can be constructed, a synthetic execution root anchors the trace instead and both spans parent onto it:

```
Synthetic execution root
├── Workflow
├── Invocation 1
├── Invocation 2
└── Invocation N
```

- **Execution ancestor** — the common parent both the Workflow and Invocation spans resolve onto. A valid remote server span (`Root` and `Parent`) is used directly, whether or not `Sampled` is present; only when a valid remote parent cannot be constructed does a synthetic execution root take its place. It is a non-recording context, not an exported span.
- **Workflow span** — one logical span per durable execution, joining the execution trace with a stable span ID derived from the ARN. Exported only on the terminal invocation (SUCCEEDED/FAILED).
- **Invocation span** — one per Lambda invocation, parented to the ambient span only when it is on the execution trace, otherwise to the execution ancestor
- **Operation span** — one per durable operation, named after your step/wait names
- **Attempt span** — one per user function execution (retries produce additional attempt spans)

Operation and attempt spans link to the Workflow span. `ExecutionOtelPlugin` reverses that relationship: operations are children of Workflow and link to the current Invocation span.

### Sampling

The plugin decides sampling once per invocation and applies that single decision to every durable span (Workflow, Invocation, operation, attempt), so the configured sampler is not re-invoked per span and the full decision — including `RECORD_ONLY` — is preserved. The decision follows this precedence, highest first:

1. **Backend decision** — `Sampled=1` / `Sampled=0` in the propagated header is authoritative and always preserved, regardless of the configured sampler.
2. **Same-trace ambient span** — when the header carries no usable `Sampled` value but a valid ambient span (for example an auto-instrumentation Lambda handler span) is already on the execution's trace, the plugin follows that span's decision: sampled → sampled; unsampled but still recording → `RECORD_ONLY`; unsampled and not recording → dropped.
3. **Configured sampler (application-owned provider)** — when you pass a `SdkTracerProvider` to the plugin, its sampler is read directly and evaluated once with the trace ID, span name, and attributes. A trace-ID-ratio sampler therefore produces a stable decision across reinvocations (the trace ID is stable).
4. **Installed sampler (Java-agent path)** — when the agent owns the provider, it is behind a classloader boundary and its *effective* sampler (which another agent extension may have wrapped or replaced) cannot be reliably read at decision time. Rather than guess, the plugin **defers**: it installs a delegating sampler through the agent's autoconfiguration and lets that wrapper consult the agent's real sampler. The delegate's decision is honored in full — if your configured policy is `always_off`, a rate limiter, or a remote sampler (`xray`, `jaeger_remote`) that returns drop, the durable spans are dropped; they are **not** force-sampled. To avoid consuming a stateful or quota-based sampler once per span, the wrapper consults the delegate once per execution (keyed by trace ID) and reuses that decision for the execution's remaining durable spans within the invocation.

For precise, provider-independent control, set an explicit `Sampled` value upstream (for example by enabling X-Ray active tracing) — that backend decision takes precedence over everything else.

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
| `durable.attempt.outcome` | SUCCEEDED (span status `OK`), FAILED (`ERROR`), or INCOMPLETE (`UNSET`) |

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

Both plugins take a required `SdkTracerProviderBuilder` (your exporter/processor pipeline) plus an optional
`OtelPluginConfig` built with a named-field builder. This replaces the older telescoping constructors, giving readable,
type-safe call sites, and matches the `OtelPluginConfig` object in the JavaScript and Python SDKs.

### InvocationOtelPlugin

```java
// Default: ADOT Java agent global provider, X-Ray context extraction, MDC enabled
new InvocationOtelPlugin();

// Custom tracer provider pipeline, all other options defaulted
new InvocationOtelPlugin(tracerProviderBuilder);

// Full configuration via the builder
new InvocationOtelPlugin(
    tracerProviderBuilder,
    OtelPluginConfig.builder()
        .contextExtractor(new XRayContextExtractor())
        .enableMdc(true)
        .workflowSpanName("Workflow")
        .instrumentationName("aws-durable-execution-sdk-java")
        .build());
```

### ExecutionOtelPlugin

The `ExecutionOtelPlugin` renders the Workflow span as the durable trace root with operations beneath it. Invocation
spans remain in the ambient Lambda trace, and operations link to the Invocation that ran them. It takes the same
`(SdkTracerProviderBuilder, OtelPluginConfig)` constructor:

```java
// Default: ADOT Java agent global provider, X-Ray context extraction, MDC enabled
new ExecutionOtelPlugin();

// Custom tracer provider pipeline, all other options defaulted
new ExecutionOtelPlugin(tracerProviderBuilder);

// Full configuration via the builder
new ExecutionOtelPlugin(
    tracerProviderBuilder,
    OtelPluginConfig.builder()
        .enableMdc(false)
        .workflowSpanName("Workflow")
        .build());
```

### OtelPluginConfig options

| Builder method | Description | Default |
|-----------|-------------|---------|
| `contextExtractor(...)` | Extracts parent trace context from the Lambda environment | `new XRayContextExtractor()` |
| `enableMdc(...)` | If true, injects `trace_id`/`span_id`/`traceSampled` into SLF4J MDC | `true` |
| `workflowSpanName(...)` | Name for the Workflow span | `"Workflow"` |
| `instrumentationName(...)` | Instrumentation scope name registered with the tracer | `"aws-durable-execution-sdk-java"` |

> The `tracerProviderBuilder` argument is not used by the no-arg `new InvocationOtelPlugin()` /
> `new ExecutionOtelPlugin()` constructors; those resolve the ADOT Java agent's global provider at invocation start.
> If it is not ready, all telemetry is disabled for that invocation and resolution is retried on the next invocation.
> A `null` passed to any `OtelPluginConfig` builder setter falls back to that option's default.

## Known Limitations

### X-Ray Segments Timeline (ungrouped view)

The plugin's spans do not appear as nested subsegments of the Lambda platform segment in the ungrouped "Segments Timeline" view. This is because the ADOT collector's OTLP-to-X-Ray conversion cannot attach exported spans as subsegments of the Lambda service's native X-Ray segment (created outside the OTLP pipeline). Use the **"Group by nodes"** view to see the full span hierarchy.

### Workflow Span

The Workflow span joins the execution trace by parenting onto the execution ancestor: the propagated remote server span when one is valid, otherwise a synthetic execution root. Either way it shares the execution trace ID and keeps its stable, ARN-derived span ID.

## Verification

After deploying your function with the plugin configured:

1. **Invoke your durable function** — trigger at least one execution that includes multiple steps or a wait/resume cycle.

2. **Check CloudWatch console** — Navigate to CloudWatch > Traces. Enable "Group by nodes" to see:
   - One execution trace covering the whole execution, with the Workflow span and each Invocation span sharing its trace ID
   - One Invocation span per Lambda invocation
   - Child spans for each durable operation (named after your step names)
   - Links between durable Workflow/operation spans and Invocation spans

3. **Check log correlation** — Verify that the Logs section at the bottom of the trace view shows both platform logs and application logs correlated with the trace.

### Troubleshooting

| Symptom | Likely Cause |
|---------|-------------|
| No traces appear | ADOT layer not added, or `AWS_LAMBDA_EXEC_WRAPPER` not set |
| Invocation spans are not parented to Lambda | X-Ray active tracing not enabled on the Lambda function |
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
