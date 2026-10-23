# AWS Durable Execution SDK - OpenTelemetry Plugin

OpenTelemetry instrumentation plugin for the AWS Lambda Durable Execution SDK for Java. Anchors every durable execution on one trace so the Workflow span and its per-invocation spans stay correlated, joining the propagated backend trace when one is present.

## Features

- **Backend-parented execution trace**: The Workflow span parents onto the execution ancestor resolved at invocation start — a propagated remote context, or a synthetic execution root — for one trace ID that is stable across all invocations, plus a stable span ID derived from the ARN
- **Ambient Invocation Traces**: Invocation spans inherit the active Lambda/X-Ray context, or join the execution ancestor so they stay on the execution trace
- **Scoped ID Generation**: Unrelated instrumentation scopes retain their provider's normal root trace ID generation
- **Span-per-Operation**: Each durable operation (step, wait, map, etc.) gets its own span with accurate timing
- **Attempt Spans**: Each user function execution (step attempt, child context run) gets a span, including retries
- **Log Correlation**: Injects `traceId`, `spanId`, and `otelTraceSampled` into SLF4J MDC for end-to-end observability
- **ADOT Java Agent Integration**: `InvocationOtelPlugin.factory()` binds the ADOT Java agent's global provider on first use, with no handler-side OpenTelemetry initialization
- **Lambda Layer Discovery**: `DURABLE_EXECUTION_PLUGINS` loads either OTel plugin from a JAR under a layer's `java/lib` directory

## One durable tracing view

Choose either the invocation or execution factory. Their `durable-otel-view` exclusive group is validated across
explicit and environment-selected factories before any invocation instance or span is created. Unrelated factories
remain allowed. `DurableConfig.toBuilder()` and `LocalDurableTestRunner` preserve the resolved factory registrations
without rediscovering environment plugins; factory identity retains environment-owned provider resources.

## Root handler context

With a core that supports the explicit `@HandlerScoped` capability, user instrumentation in the root handler joins
its canonical execution trace. A valid same-trace ambient Lambda span stays current. If ambient context is absent or
belongs to another trace, invocation view activates `Invocation`; execution view activates the deterministic
`Workflow` context, including its unsampled non-recording form. Nested operations retain their existing contexts.

The core opens the explicit scope after invocation startup and closes it on the same handler thread on success,
failure, or suspension. The handler owns the entire invocation lifecycle: Start, body and scope unwinding, operation
and checkpoint drain, output preparation, then End in reverse plugin registration order. The caller waits for that owner and
for End to finish. A zero or expired runtime budget does not move End onto the caller or allow root scope cleanup
to continue after End. A compatible ambient span uses a no-op scope with the same lifecycle ordering.

Ordinary cleanup preserves the previously selected durable outcome. Recognized `VirtualMachineError` or `ThreadDeath`
from owned scope callbacks propagates after the owner has completed cleanup; started plugins receive one RETRYING End.
The SDK cannot bound arbitrary root code, scope close callbacks, serializers, provider callbacks, or transports. Its
fatal signal still interrupts cooperative peers and rejects queued work, but the invocation joins actual peer tasks,
admitted continuations, and in-flight transport before End. A fatal no longer enables a 500 ms return while those tasks
remain active. Non-cooperative work can therefore delay End and caller return without a hard bound. Operation owners
retain their matching cleanup recipients through that drain, while concurrent invocations create distinct instances. Shared provider resources remain environment-owned.

The JDK-only opener requires explicit `@HandlerScoped` opt-in; unannotated application methods are not invoked. The
factory migration follows the 3.x artifact boundary in the migration guide. Nonfatal scope-open/close failures remain
contained, and earlier scopes still close in reverse acquisition order. Root-handler context requires the matching
core/plugin pair.

The core observes the handler and manager outcomes before executor dispatch. A later ordinary `finally` return or
failure does not replace an already selected suspension or retry. Diagnostics during ordinary failure-response
preparation share its existing guard: if diagnostic access fails, started plugins receive one RETRYING End before
that preparation failure propagates.

Root-handler JVM fatals recognized by the existing body classifier are reported before owned scopes close so peer
work can be stopped cooperatively, but the root owner still finishes cleanup before End. The first accepted body fatal
remains primary if close raises another fatal. Earlier selected suspension/retry control still wins over a later body
`finally` failure; a recognized losing body fatal escapes its actual asynchronous worker after that selected outcome
and End are delivered. A result-serialization fatal is discovered only after normal peer draining, so it cannot shorten
that earlier drain.

After a fatal abort, checkpoint and state-page responses are checked before local state/callback/poller publication.
A response that observes the fatal at a guard stops publication; persisted backend state remains available for normal
replay. These guards are not atomic cancellation: work already past a check can race with abort and finish, including
callbacks and poller completion. If a poller callback aborts the invocation, the remaining detached observers settle
with that fatal while earlier delivered outcomes remain unchanged. The transport and poller locks are unchanged;
these checks do not interrupt the transport or create a new caller-outcome policy.

Successful-output preparation closes new checkpoint-continuation admission before serialization. It does not revoke
user workers already admitted to their executor, and End does not mean all unawaited work has finished. The separate
late-worker and mandatory large-result acknowledgment boundaries in [#787](https://github.com/aws/aws-durable-execution-sdk-java/issues/787)
remain outside this change.

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-plugin-otel</artifactId>
    <version>${durable.sdk.version}</version>
</dependency>
```

For the agent path (`InvocationOtelPlugin.factory()`), no additional OpenTelemetry dependencies are needed — the ADOT Java agent layer provides them.

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

## Chained-invoke propagation

Both views provide the calling operation's X-Ray context to the SDK's synchronous collector. New invoke START
checkpoints carry that context in the flat optional `ChainedInvokeOptions.XAmznTraceId` field. The root POM's
AWS SDK for Java 2.56.0 provides the generated typed setter. Deployed parent/child trace behavior still requires
cloud validation. See [the implemented path and validation status](../docs/advanced/propagation-metadata.md).

Execution-view operation spans are deferred until completion. Their placeholders can provide sampling metadata with
a visible provider using the SDK's `DurableSampler`, or an agent provider whose `DurableSampler` confirms that it
consumed this invocation's sampling intent. Confirmation crosses the application/agent class-loader boundary through
the existing thread-scoped bridge and is cleared after span creation. The propagated parent is the deterministic
invoke span ID, and sampling flags come from the actual Invocation span.
Replacement samplers and opaque providers without that confirmation still abstain; the backend's existing
omitted-header fallback applies. Invocation view uses the actual started operation span's flags. Before that span is
available, it requires a visible durable sampler. Neither path changes the customer's sampler, samples again, or
creates a span just to obtain propagation metadata.

## Fallback execution roots

When the backend supplies no complete remote parent, both views create and immediately end a `DurableExecutionRoot`
anchor during per-invocation factory creation, before descendant spans and customer code. Its trace and span IDs are
deterministic; both timestamps are the checkpointed execution start. The anchor has
`durable.execution.synthetic_root=true` and carries identity, not execution duration or outcome. Workflow remains the
terminal summary. Complete remote parents are externally owned and are never exported by the SDK.

With the managed `DurableSampler`, anchors use only the sampled decision and stable SDK identity. Custom sampler
attributes and trace state remain on Workflow, Invocation, operation and attempt descendants; they are not copied to
re-exported anchors. `RECORD_ONLY` still records descendants but produces no anchor, and `DROP` produces neither.
Visible plain replacement providers retain their documented independent per-span policy described below.

Each invocation may re-export the zero-duration anchor to recover an earlier lost export. Managed sampled re-exports
keep identity, timestamps and SDK span fields stable. Provider resources still apply and may differ between execution
environments. No export or flush failure can guarantee delivery. Invocation End attempts one flush even if span
finalization fails. A factory that fails before returning an instance keeps the major's existing containment/skipping
contract; the SDK does not publish a partially constructed plugin just to give it an End callback.

After a nonfatal construction failure, the OTel factory makes a best-effort flush of completed spans through its
already bound, visible SDK provider, using the same five-second result wait as End. It never shuts down the shared
provider or recovers every partially opened span. A recognized original `VirtualMachineError` or `ThreadDeath`
(including the supported asynchronous wrappers) propagates before any additional provider flush; completed telemetry
can remain buffered on that fatal path. For nonfatal startup failures, ordinary flush problems retain the original
failure, while a JVM fatal from flushing still propagates. Opaque providers have no added flush guarantee, and the
result wait does not preempt an arbitrary synchronous processor callback.

## Choose one durable OTel view

Configure exactly one of `InvocationOtelPlugin.factory(...)` or `ExecutionOtelPlugin.factory(...)` when enabling
tracing. Both emit Workflow and Invocation telemetry and manage log correlation. Configuration rejects multiple
factories in the `durable-otel-view` exclusive group before creating any invocation-owned plugin, including repeated
explicit factories and conflicting environment/explicit registrations. Either single view and unrelated factories
remain valid. The factory migration uses `DurableExecutionPluginFactory.getExclusiveGroup()` metadata; it does not
inspect arbitrary methods on the plugin instances or construct them during validation.

`config.toBuilder()` preserves the resolved factory list without rediscovery. Calling `withPlugins(...)` replaces
that complete list, and `withPlugins()` removes every factory. Use a fresh `DurableConfig.builder()` to read the
current environment selection. The 3.x testing SDK uses this copy path; the 2.x instance-registration/testing SDK
compatibility path is outside this major-version migration.

View exclusivity is declared with inherited `@ExclusivePluginGroup("durable-otel-view")` metadata.
Configuration reads this explicit opt-in annotation from the entire superclass chain; it does not call application methods
that happen to be named `getExclusiveGroup`. Existing subclasses retain their own methods while inheriting the bundled
view restriction. A subclass may add another group, but cannot replace a superclass's group; repeated group names in one
class hierarchy are checked once.
The exclusivity annotation does not change the provider registration API; the same-thread core requirement above still applies.

## Quick Start using X-Ray/CloudWatch Tracing (ADOT Java Agent)

1. Add the ADOT Lambda Layer to your function
2. Enable X-Ray Active Tracing on the function
3. Configure environment variables
4. Load `InvocationOtelPlugin` dynamically or register it in your handler's `DurableConfig`
5. Grant X-Ray write permissions

### 1. ADOT Lambda Layer

This plugin uses the [AWS Distro for OpenTelemetry (ADOT) Lambda layer](https://aws-otel.github.io/docs/getting-started/lambda) for trace export. `InvocationOtelPlugin.factory()` resolves the global provider initialized by the ADOT Java agent when the first invocation's plugin instance is created, with deterministic span ID generation installed through the plugin's `AutoConfigurationCustomizerProvider` SPI. If the provider is not ready, that invocation's instance emits no telemetry and the next invocation's instance resolves the provider again.

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

### Invocation-local headers on Lambda Managed Instances

The SDK captures `Context.getXrayTraceId()` on the runtime thread before dispatching user work when an actual
runtime implementation overrides the accessor. It stores the immutable snapshot in `InvocationInfo.xRayTraceId()`.
The same information object reaches the ordinary `createPlugin(info)` factory and `onInvocationStart(info)` hook,
so both built-in and custom factories can read the header before creating spans. Context extraction uses `extract(info)`;
existing no-argument extractor implementations retain their default delegation.

An override returning null/empty is represented by an empty String and is authoritative absence: deterministic fallback
is used without reading stale global context. An unavailable accessor, or only Lambda Core1.4's inherited null-returning
default, is represented by null and retains ordinary system-property then environment fallback. A malformed captured
header also never adopts the global carrier. No global header is modified.

Capture is skipped when no plugin factories are registered. A nonfatal failure from an available runtime accessor
also becomes authoritative absence, so it cannot reuse another invocation's global trace or sampling decision.
Direct JVM-fatal errors propagate with their original identity, as do fatal errors wrapped in a `CompletionException`,
`ExecutionException`, `InvocationTargetException`, or `UndeclaredThrowableException`.

In 3.x, the header is the eighth `InvocationInfo` record component. Existing 4-, 5-, 6-
and 7-argument constructors remain and default the optional header to null. Existing compiled constructor/accessor calls
and seven-component record patterns remain compatible; recompiling a seven-component record pattern requires an eighth
binding. Reflection sees eight components, and record equality/hash calculation includes the header. `toString()` omits it.
Core/plugin artifacts still follow the explicit 3.x factory migration boundary: rebuild providers and layers against 3.x
as described in the migration guide; 2.x and 3.x plugin artifacts are not interchangeable.

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
        // A factory, not a plugin instance: the SDK creates one plugin instance per invocation from it.
        return DurableConfig.builder().withPlugins(InvocationOtelPlugin.factory()).build();
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

The OpenTelemetry dependency guard and hook linkage isolation are inherited from the 2.x fix in
[#780](https://github.com/aws/aws-durable-execution-sdk-java/pull/780). The 3.x factory migration described above still
requires rebuilt SDK plugin providers; it does not provide a cross-major compatibility bridge. Fatal JVM errors and
`ThreadDeath` propagate. Align incompatible dependencies to restore instrumentation; error isolation does not make
every old agent/API combination capable of exporting telemetry.

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
span. Workflow, Invocation, operation and attempt spans preserve the full result, including `RECORD_ONLY`, sampler
attributes and trace state. The re-exported `DurableExecutionRoot` is the narrow exception described above: it uses only
the sampled decision and stable SDK identity, and is omitted for `RECORD_ONLY` or `DROP`.

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

For a visible plain replacement sampler with no explicit upstream or same-trace ambient decision, a fallback anchor
uses the provider's actual root sampling result for its parent context. It does not spend a preliminary decision before
creating that root. Descendants still use the provider's own per-span sampling, so a stateful policy can select them
differently. Explicit and ambient decisions retain their existing context behavior. The legacy complete-remote-parent
path still evaluates root policy before span creation when sampling is undecided; a stateful replacement can observe
that additional query. The query resolves the remote parent's otherwise-unknown sampled flag from the configured
root policy; omitting it can bypass `ParentBased(alwaysOff)`. Supply an explicit upstream decision or retain
`DurableSampler` when a policy result must be reused authoritatively. A plain replacement receives no SDK sampling carrier and does not provide SDK-wide decision
reuse or full-result overrides. Opaque providers cannot expose a later sampler replacement to the
application, so retain the wrapper through the documented customization setup.

For explicit execution-level control, keep `DurableSampler` as the final installed sampler and set `Sampled` upstream,
for example through X-Ray
active tracing. The SDK sampling wrapper preserves that decision.

Parentless fallback-root exports also use the provider's sampler. If the SDK wrapper is replaced by a plain
sampler, those anchors can follow its normal root policy independently of the flags used by their descendants;
full upstream sampling overrides require the installed SDK wrapper. For example, a sampler that selects spans by name
can export only the anchor or only its Invocation child. This behavior is covered for both views by
`ExecutionRootParityTest.plainReplacementRetainsIndependentPerSpanSampling`; installing `DurableSampler` restores the
shared decision. Merely delaying anchor creation until terminal completion would not make an arbitrary replacement
sampler consistent across spans.

### Operation error details

An explicit operation status of `FAILED`, `TIMED_OUT`, or `STOPPED` sets the OTel span to `ERROR`, including when the
backend error container is absent or fully empty. No exception event is recorded when no details were supplied.
An explicitly present empty string or stack-trace list still counts as supplied error details.
Virtual (FLAT) contexts can have no terminal status; a non-null failure still sets `ERROR`, even when its backend
error container supplies no details. That failure has no exception event unless details are available.
An exception's own message (including an empty string), cause, suppressed failures,
or explicit stack trace still supplies diagnostic information and retains `ERROR` and its exception event.

This telemetry decision does not remove the SDK's failure notification: a present `ErrorObject`, including `{}`,
remains a `DurableOperationException` in operation hooks and snapshots. An absent container retains its existing
null metadata behavior. Persisted errors, caller failures, replay, and user-function error handling are unchanged.

Execution-view operation spans with a known, nonzero backend end timestamp record the exception event at that
same timestamp. This path uses the public `exception` event with standard `exception.type`, `exception.message`
(when present), and `exception.stacktrace` attributes. Java's standard stack-trace text and the provider's ordinary
event attribute count/value limits apply; native `recordException` resolver behavior is not preserved on this path.
Invocation-view events and operations without a backend end (including the SDK's epoch-zero clock sentinel) keep
native `recordException` behavior.

When an Execution-view completion has no observed or backend start timestamp, its known end also supplies the start.
Existing earliest-known-start selection is unchanged and is not clamped to the backend end, so clock skew can still
produce an inverted interval. This correction does not add a broader clock or containment policy.

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

The core preserves worker MDC through the handler-owned End callback, then restores the previous worker map before
publishing the return to the caller. A recognized fatal from restoration propagates to the caller and actual worker,
including after an ordinary handler failure. End still describes the already selected SDK outcome and is not dispatched
a second time to describe a later MDC-restoration failure. An already known fatal remains primary over cleanup errors.

After an ordinary outer restoration failure, the core attempts `MDC.clear()` once and exposes the cleanup failure on the
asynchronous worker while retaining the selected ordinary caller outcome. This also applies when an earlier handler fatal
remains primary. With a standard `ThreadPoolExecutor`, the escaped failure retires that worker; a successful clear keeps
invocation MDC from being inherited by its replacement. Clearing can discard ambient MDC that could not be restored.
A caller-owned executor may intercept failures, and an adapter that rejects clear cannot guarantee a clean replacement.
Factory APIs and handler-body classification are unchanged. With no plugins, the outer worker does not capture or clear
MDC; input failure before the durable logger scope leaves application-owned MDC untouched. Once that logger scope starts,
its existing cleanup behavior still applies. Pre-start plugin MDC capture uses bounded standard-wrapper fatal detection,
settles observation before throwing the original fatal, and creates no plugin or hook. Ordinary capture failures retain
their existing classification. Terminal failure payloads are prepared before End; an encoding failure reports RETRYING.

Configure your logging framework (e.g., Log4j2) to include MDC fields in the output. For example, using `JsonLayout`:

```xml
<Console name="Console" target="SYSTEM_OUT">
    <JsonLayout compact="true" eventEol="true" properties="true" />
</Console>
```

With Lambda's `LoggingConfig: JSON` (required for durable functions), CloudWatch parses the JSON and X-Ray correlates logs via `requestId` (injected by the core SDK's `DurableLogger`).

## Configuration

Each plugin is registered as a `DurableExecutionPluginFactory` obtained from its static `factory(...)` methods, because
a plugin instance serves exactly one invocation: the SDK calls the factory once per invocation and drops the instance
when the invocation returns. The factory holds what belongs to the execution environment — your tracer provider (built
once) or the ADOT global provider binding, plus the deterministic ID generator — while each instance holds only its own
invocation's spans.

The `factory(...)` overloads take an optional `SdkTracerProviderBuilder` (your exporter/processor pipeline) plus an
optional `OtelPluginConfig` built with a named-field builder, which matches the `OtelPluginConfig` object in the
JavaScript and Python SDKs.

### InvocationOtelPlugin

```java
// Default: ADOT Java agent global provider, X-Ray context extraction, MDC enabled
InvocationOtelPlugin.factory();

// Custom tracer provider pipeline, all other options defaulted
InvocationOtelPlugin.factory(tracerProviderBuilder);

// Full configuration via the builder
InvocationOtelPlugin.factory(
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
spans remain in the ambient Lambda trace, and operations link to the Invocation that ran them. It exposes the same
`factory(SdkTracerProviderBuilder, OtelPluginConfig)` methods:

```java
// Default: ADOT Java agent global provider, X-Ray context extraction, MDC enabled
ExecutionOtelPlugin.factory();

// Custom tracer provider pipeline, all other options defaulted
ExecutionOtelPlugin.factory(tracerProviderBuilder);

// Full configuration via the builder
ExecutionOtelPlugin.factory(
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

> The no-builder `InvocationOtelPlugin.factory()` / `ExecutionOtelPlugin.factory()` forms resolve the ADOT Java agent's
> global provider instead, when the first invocation's instance needs it. If it is not ready, all telemetry is disabled
> for that invocation and the next invocation's instance resolves it again.
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

var otelPluginFactory = InvocationOtelPlugin.factory(
        SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create())));
```

## Requirements

- Java 17+
- AWS Durable Execution SDK for Java 3.x and plugin factory API 2
- OpenTelemetry SDK 1.65.0+ (only for custom TracerProvider path)
- ADOT Lambda Layer `AWSOpenTelemetryDistroJava` (for the agent path, `factory()` without a builder)

## License

Apache-2.0

### Installed core/plugin layer compatibility checks

The retained `src/test/compatibility/run_matrix.py` is the2.x regression harness for the additive minor fixes. It
verifies released/current2.x artifacts in separate plugin-layer loaders without dependency-floor rejection. It is not
a 3.x compatibility bridge: the factory/provider migration deliberately requires 3.x artifacts and rebuilt provider
layers. The major's provider migration tests validate clear rejection of selected legacy providers; the minor PRs
retain their independently tested2.x compatibility guarantees.

The inherited `@HandlerScoped` annotation names an explicit JDK `Function` opener. The core passes the plugin
instance to it without discovering a plugin method by name. Bundled openers call private SDK implementation code.
Custom opener classes need a public no-argument constructor accessible to the core. The factory/provider migration
still requires 3.x artifacts and intentionally final view classes; this is not a cross-major subclass compatibility bridge.
