# AWS Durable Execution SDK - OpenTelemetry Plugin

OpenTelemetry instrumentation plugin for the AWS Lambda Durable Execution SDK for Java. Anchors every durable execution on one trace so the Workflow span and its per-invocation spans stay correlated, joining the propagated backend trace when one is present.

## Features

- **Backend-parented execution trace**: The Workflow span parents onto the execution ancestor resolved at invocation start — a propagated remote context, or a synthetic execution root — for one trace ID that is stable across all invocations, plus a stable span ID derived from the ARN
- **Ambient Invocation Traces**: Invocation spans inherit the active Lambda/X-Ray context, or join the execution ancestor so they stay on the execution trace
- **Scoped ID Generation**: Unrelated instrumentation scopes retain their provider's normal root trace ID generation
- **Span-per-Operation**: Each durable operation (step, wait, map, etc.) gets its own span with accurate timing
- **Attempt Spans**: Each user function execution (step attempt, child context run) gets a span, including retries
- **Log Correlation**: Injects `traceId`, `spanId`, and `otelTraceSampled` into SLF4J MDC for end-to-end observability
- **ADOT Java Agent Integration**: `new InvocationOtelPlugin()` late-binds the ADOT Java agent's global provider with no handler-side OpenTelemetry initialization
- **Lambda Layer Discovery**: `DURABLE_EXECUTION_PLUGINS` loads either OTel plugin from a JAR under a layer's `java/lib` directory

## Checkpoint continuation failures

An operation's SDK checkpoint continuation reports resumption/deserialization and dispatch failures through
retryable invocation control before releasing its activity lease. End reports `RETRYING`, the caller receives
`UnrecoverableDurableExecutionException` with the original cause, and persisted operation state remains available
for a later invocation. Rejected worker admission releases its activity registration. Direct `VirtualMachineError`
and `ThreadDeath` also settle the continuation observation before escaping its coordinator worker.

Ordinary unowned helper failures retain their observation-only behavior. Normal manager closing does not replace
the selected outcome or stop unrelated operations. This boundary does not change handler/predicate failure
classification, hook threading, trace topology, or add general wrapped-fatal classification.

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
    <version>1.65.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-logging</artifactId>
    <version>1.65.0</version>
</dependency>
```

## Fallback execution roots

When the backend supplies no complete remote parent, both views export a `DurableExecutionRoot` anchor before the first
invocation returns, including when it suspends with `PENDING` or fails with `RETRYING`.
The anchor is started before descendants so their parent context includes its provider-resolved trace flags and
trace state. It ends at invocation end, before flushing, with the same fixed execution-start timestamp. For a
deferred sampler, the first actual fallback span is now `DurableExecutionRoot`; custom policies that depend on the
first span name or attributes can observe that ordering. No arbitrary name/input invariance is promised.

The anchor is marked `durable.execution.synthetic_root=true`. Its trace and span IDs are deterministic and its start
and end timestamps are the checkpointed execution start. It does not report execution status or duration; `Workflow`
continues to report those at terminal completion. Complete remote parents remain externally owned and are never exported.
Java's `InvocationInfo` requires a non-null execution start timestamp from the initial checkpointed execution operation.
Missing timestamps are rejected before the plugin runs; anchor timestamps never fall back to the current wall clock.

Every invocation may re-export the anchor to recover from an earlier interrupted or lost export. Re-exports retain the
same span fields under stable sampling. The existing provider resource still applies: if a later invocation runs in another
execution environment, resource attributes such as `faas.instance` can differ. Backends that deduplicate by span identity
may retain either copy's resource. Each execution ARN owns its own anchor even when multiple executions share a
propagated trace ID without a parent; they are not collapsed into one
execution. With the SDK sampler retained in the provider, upstream sampling and configured fallback sampling apply
to anchors and their descendants together.

After the first invocation's export and flush succeed, its anchor remains available even if the execution is stopped
or times out while suspended and never invokes the plugin again. An invocation killed before its end hook or flush can
still lose its spans; a later invocation, including terminal completion, attempts to export the anchor again. Export
and flush failures do not provide a delivery guarantee.

For a consistent hierarchy across invocations, preserve an explicit upstream sampling decision or use a deterministic
sampling policy based on the stable trace ID. A non-deterministic sampler can export an anchor without a Workflow, or
a Workflow without its anchor. Any sampler-supplied attributes and trace state must also stay stable for identical
anchor re-exports.

## Choose one durable OTel view

Configure exactly one of `InvocationOtelPlugin` or `ExecutionOtelPlugin` when enabling durable tracing.
The invocation view groups work by Lambda invocation; the execution view groups operations under the durable Workflow.
Both create Workflow and Invocation telemetry and manage log correlation, so combining them is unsupported.
`DurableConfig.Builder.build()` rejects conflicting views before lifecycle hooks run and names both plugins in the
diagnostic. It throws `IllegalStateException` with the `Dynamic plugin configuration failed: ` prefix used for other
plugin-configuration errors.
This applies to explicit registration, `DURABLE_EXECUTION_PLUGINS=otel-invocation,otel-execution`, and mixed registration.
Zero OTel plugins, either single view, and unrelated plugins remain valid.
Repeated explicit registrations of the same view are also rejected, including registering the same instance twice.
When an environment-selected exclusive plugin's exact concrete type is already explicitly configured, discovery keeps
that explicit instance and skips constructing another. This preserves configuration copies made by released testing
SDK 2.2.1 without duplicate telemetry. Different subclasses still participate in exclusive-group validation; plugins
without exclusive-group metadata retain their existing multi-instance behavior. The current testing SDK copies
resolved plugin instances without rediscovery.

`config.toBuilder()` keeps that resolved-list behavior for the lifetime of the copied builder. Calling `withPlugins(...)`
on it replaces the complete plugin list without reading `DURABLE_EXECUTION_PLUGINS` again; `withPlugins()` removes all
plugins from the copy. Use `DurableConfig.builder()` when creating a fresh configuration that should honor the current
environment selection.

View exclusivity is declared with inherited `@ExclusivePluginGroup("durable-otel-view")` metadata.
Configuration reads this explicit opt-in annotation from the entire superclass chain; it does not call application methods
that happen to be named `getExclusiveGroup`. Existing subclasses retain their own methods while inheriting the bundled
view restriction. A subclass may add another group, but cannot replace a superclass's group; repeated group names in one
class hierarchy are checked once.
Older cores ignore the optional annotation and retain their prior behavior; no provider-version floor is raised.

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

### OpenTelemetry version compatibility

Keep the OpenTelemetry API, context, SDK, and Java agent versions aligned. This plugin is built and tested against
OpenTelemetry 1.66.0. Global-provider binding needs `GlobalOpenTelemetry.isSet()` and `getOrNoop()`; when the visible
API lacks either method (for example, API 1.49.0), the plugin logs a compatibility diagnostic and disables its telemetry
for that invocation. It does not install a no-op global that would prevent a provider from being registered later.

The existing 2.x plugin constructors, registration interfaces, and instance lifetime are retained. Nonfatal linkage
errors from plugin callbacks are logged and isolated so healthy plugins and the handler can continue. Fatal JVM errors
and `ThreadDeath` retain their existing propagation behavior. Provider registration and configuration validation remain
unchanged. Align incompatible dependencies to restore instrumentation; error isolation does not make every old
agent/API combination capable of exporting telemetry.

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
DurableExecutionRoot (materialized and re-exported each invocation)
├── Workflow
├── Invocation 1
├── Invocation 2
└── Invocation N
```

- **Execution ancestor** — the common parent both the Workflow and Invocation spans resolve onto. A valid remote server span (`Root` and `Parent`) is used directly, whether or not `Sampled` is present; only when a valid remote parent cannot be constructed does a synthetic execution root take its place. The remote ancestor is used as a non-recording context; the synthetic ancestor is materialized as `DurableExecutionRoot` and re-exported on each invocation with its stable span ID and execution start time, subject to sampling.
- **Workflow span** — one logical span per durable execution, joining the execution trace with a stable span ID derived from the ARN. Exported only on the terminal invocation (SUCCEEDED/FAILED).
- **Invocation span** — one per Lambda invocation, parented to the ambient span only when it is on the execution trace, otherwise to the execution ancestor
- **Operation span** — one per durable operation, named after your step/wait names
- **Attempt span** — one per user function execution (retries produce additional attempt spans)

Operation and attempt spans link to the Workflow span. `ExecutionOtelPlugin` reverses that relationship: operations are children of Workflow and link to the current Invocation span.

### Sampling

The SDK-wide sampling guarantees below require `DurableSampler` itself to be the final installed sampler. The
[builder constructors](#configuration) and [ADOT extension setup](#1-adot-lambda-layer) install it automatically;
configure its delegate through these documented paths and retain `DurableSampler` as the final sampler. An
unrecognized outer wrapper is treated as a plain replacement. The SDK wrapper applies its sampling intent to every durable
span (DurableExecutionRoot, Workflow, Invocation, operation and attempt), preserving the full result, including `RECORD_ONLY`, sampler attributes
and trace state.

The supported SDK sampler follows this precedence, highest first:

1. **Backend decision** — with `DurableSampler` installed, `Sampled=1` / `Sampled=0` in the propagated header is
   authoritative, regardless of the wrapped delegate's policy.
2. **Same-trace ambient span** — without an explicit header decision, a valid ambient span already on the execution
   trace supplies its decision: sampled → sampled; unsampled and recording → `RECORD_ONLY`; unsampled and
   non-recording → dropped.
3. **Same-copy durable sampler** — the visible SDK sampler is evaluated against root context once per invocation
   using the canonical trace ID, span name and attributes. Its complete result is carried in that loader's context.
4. **Foreign or opaque SDK sampler** — resolution is deferred to the installed wrapper's actual delegate. Its full
   result is cached by execution ARN and canonical trace ID in a 256-entry LRU cache and reused while resident.
   Eviction can cause another evaluation. The delegate still receives the canonical trace ID; drop and
   `RECORD_ONLY` decisions, attributes and updated trace state are retained.

A visible plain replacement sampler supplies its root policy for execution-ancestor flags, then keeps its normal
per-span sampling behavior. It receives no SDK sampling carrier and does not provide the SDK decision-reuse
or full-result override guarantees above. Opaque providers cannot expose a later sampler replacement to the
application, so retain the wrapper through the documented customization setup.

For explicit execution-level control, keep `DurableSampler` as the final installed sampler and set `Sampled` upstream,
for example through X-Ray
active tracing. The SDK sampling wrapper preserves that decision.

Parentless fallback-root exports also use the provider's sampler. If the SDK wrapper is replaced by a plain
sampler, those anchors can follow its normal root policy independently of the flags used by their descendants;
full upstream sampling overrides require the installed SDK wrapper.

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
| `traceId` | W3C trace ID (32 hex chars) |
| `spanId` | Current span ID (16 hex chars) |
| `otelTraceSampled` | Whether the trace is sampled (true/false) |

The `traceId` is also injected at invocation start so handler-level logs (between steps) include it.

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
| `enableMdc(...)` | If true, injects `traceId`/`spanId`/`otelTraceSampled` into SLF4J MDC | `true` |
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
- OpenTelemetry SDK 1.65.0+ (only for custom TracerProvider path)
- ADOT Lambda Layer `AWSOpenTelemetryDistroJava` (for the no-arg constructor path)

## License

Apache-2.0
