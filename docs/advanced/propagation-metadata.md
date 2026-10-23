# Chained-invoke trace propagation

For a new `CHAINED_INVOKE` operation, `InvokeOperation.startInvocation()` collects optional metadata after
`onOperationStart` has established the operation's span and before checkpoint transport serialization. The customer
payload first uses its existing serializer, so a payload failure does not trigger optional metadata collection. It writes
that header through the generated `ChainedInvokeOptions.builder().xAmznTraceId(...)` method. The wire member is the
flat optional `XAmznTraceId`; there is no nested `PropagationMetadata` structure or W3C transport field.

Function name, tenant ID, payload, operation name, ID, parent ID and checkpoint behavior retain their existing
paths. Each operation carries its own header, including when several invokes share a checkpoint request. No
execution-global or HTTP-request trace header can substitute for those distinct calling-operation parents.

## Plugin contract and fallback

`PropagationInput` is an immutable SDK-owned snapshot with `executionArn`, `operationId`, optional `parentOperationId`,
and `targetFunctionName`. `PropagationMetadata` is an immutable SDK-owned contribution with optional `xAmznTraceId`.
Both types use only Java strings; generated Lambda and OTel model types do not enter the plugin interface.
The typed default hook adapts a String-only producer overload, so bundled plugin layers do not reference newly added
core classes. This does not remove the bundled plugins' existing requirement for the same-thread invocation-hook
capability: cores without it, including released 2.2.1, are rejected at construction. Collection also requires a core
that implements this hook; registration/provider API versions and existing hooks are unchanged.

The optional synchronous `providePropagationMetadata` default method returns null. The dispatcher supplies the same
immutable snapshot in configured order. The first non-null supported member wins; equal values do not conflict.
Different later values warn with the winning and conflicting plugin identities and a conflict count, without logging
the header. Null contributions abstain; blank headers and ordinary hook failures are logged and skipped. A missing
contribution omits the transport member so the backend can retain its existing inherited-header fallback.
The collector follows the event hooks' isolation policy: `Exception` (including `CancellationException`) and
`LinkageError` are logged and skipped, so an incompatible optional plugin does not prevent later contributions or
the invoke START checkpoint. Other `Error` types, including JVM-fatal errors, propagate with their original identity.
Runtime exceptions from the logging backend do not interrupt collection.

Both OTel views encode the resolved canonical trace ID, actual calling-operation span ID and sampling decision as
`Root=1-<8 hex>-<24 hex>;Parent=<16 hex>;Sampled=0|1`. They require active invocation ownership. The producer does not
create a span or sample again, and retains the configured provider/resource and upstream-parent behavior. Explicit
upstream `Sampled=0` is preserved. The producer can derive an initial operation ID before a start hook, but the real
invoke path collects after that hook and therefore uses the operation context it already established.

The invocation view can read the sampling flags from an operation span that has already started. The execution view
defers that span until completion, so its placeholder does not establish a per-span sampling decision. It contributes
metadata when the visible provider uses `DurableSampler`, including the application-owned builder setup. With an
opaque Java-agent provider, the existing autoconfiguration SPI's post-build callback checks the actual provider's final
sampler. Only an unwrapped `DurableSampler` is accepted, and only that exact instance can confirm consumption of the
actual Invocation span's sampling intent through the thread-scoped bridge. A filter around `DurableSampler` cannot
authorize propagation merely by delegating Invocation sampling. A new configuration clears the previous verification;
an absent callback or an unrecognized final sampler leaves propagation disabled for an opaque provider.
The confirmation matches the trace ID, span name and execution ARN, restores an enclosing
observation when nested, and is cleared when the span-creation scope closes. This lets the producer use the deterministic
invoke parent ID and actual invocation sampling flags without accessing the agent's provider.
Replacement samplers and opaque providers without confirmation still abstain. In those cases the backend's omitted-header
fallback applies; no new invoke-parent linkage is promised. The invocation view still requires a visible durable sampler
if asked before an operation span is available.
This does not change the configured sampler, create an early operation span, or query the sampler again.

## Replay and retry

Replaying a stored START polls for the existing operation; replaying a terminal operation returns or raises its
stored outcome. Neither path collects metadata or sends another START. If a START checkpoint failed or was lost before
commit, a later invocation can create it again and collect again. Plugins must be deterministic and side-effect free;
this hook is not exactly-once delivery. Checkpoint batching and retries preserve each submitted operation's options.

## Model and backend dependencies

The [`aws.sdk.version` property in the root POM](../../pom.xml) selects AWS SDK for Java **2.56.0**. Its official Lambda
model defines optional `ChainedInvokeOptions.XAmznTraceId` on the chained-invoke START update. The published Maven
artifact provides the typed builder setter and getter. Direct checks of its generated marshaller preserve sampled
and unsampled headers through model copies and omit an absent field instead of sending null. The model update has
also passed the [normal CI build on Java 17, 21 and 25](https://github.com/aws/aws-durable-execution-sdk-java/actions/runs/38635334261/attempts/2).
No copied model artifact, reflection fallback, serializer replacement or raw HTTP field injection is required.

Distributed-map propagation is a separate path. This Java SDK currently exposes no distributed-map operation or
START dispatch; its existing `map` and `parallel` APIs use CONTEXT operations. No distributed-map, fanout or HTTP API
is introduced here. A future high-level distributed-map operation must integrate its corresponding model path.

Published-model, serialization and build verification are separate from deployed backend behavior. Parent/child traces
for durable and ordinary Lambda targets still require cloud validation of header persistence and forwarding. That
coverage is tracked in [shared conformance PR #131](https://github.com/aws/aws-durable-execution-conformance-tests/pull/131).
The client-side checks above do not claim that deployed trace topology has been verified. This remains related to
issue #764; runtime inbound headers and plugin-instance lifetime are separate changes.

Tests exercise real public invoke and checkpoint paths, pending/terminal replay, failed uncommitted START recovery,
ordinary and linkage-error plugin fallbacks and other Error propagation, sampled/unsampled OTel views, batched invokes
with distinct parents, custom payload and tenant preservation, generated-model copies, and the normal Lambda client's JSON marshaller with
only HTTP transport replaced. Batch-boundary tests include the encoded options and trace header in the 750 KiB size
budget, including JSON escaping and UTF-8. Generated-model checks use the official published dependency.
