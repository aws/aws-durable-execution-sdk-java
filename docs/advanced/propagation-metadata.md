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
core classes. Older cores continue to load those layers and retain their original behavior. New collection requires
an updated core; registration/provider API versions and existing hooks are unchanged.

The optional synchronous `providePropagationMetadata` default method returns null. The dispatcher supplies the same
immutable snapshot in configured order. The first non-null supported member wins; equal values do not conflict.
Different later values warn with the winning and conflicting plugin identities and a conflict count, without logging
the header. Null contributions abstain; blank headers and ordinary hook failures are logged and skipped. A missing
contribution omits the transport member so the backend can retain its existing inherited-header fallback.
The collector preserves Java's current event dispatch policy: `Exception` (including `CancellationException`) is
contained, while `Error` propagates. A failing logging backend does not interrupt collection.

Both OTel views encode the resolved canonical trace ID, actual calling-operation span ID and sampling decision as
`Root=1-<8 hex>-<24 hex>;Parent=<16 hex>;Sampled=0|1`. They require active invocation ownership. The producer does not
create a span or sample again, and retains the configured provider/resource and upstream-parent behavior. Explicit
upstream `Sampled=0` is preserved. The producer can derive an initial operation ID before a start hook, but the real
invoke path collects after that hook and therefore uses the operation context it already established.

## Replay and retry

Replaying a stored START polls for the existing operation; replaying a terminal operation returns or raises its
stored outcome. Neither path collects metadata or sends another START. If a START checkpoint failed or was lost before
commit, a later invocation can create it again and collect again. Plugins must be deterministic and side-effect free;
this hook is not exactly-once delivery. Checkpoint batching and retries preserve each submitted operation's options.

## Model and backend dependencies

The SDK path and tests are implemented assuming the reviewed generated model member exists. The pinned public Lambda
model `2.55.6` currently lacks `ChainedInvokeOptions.Builder.xAmznTraceId(String)`, so compilation against that model is
expected to fail. The implementation does not hide the missing member with reflection, runtime capability checks,
serializer bypasses or raw HTTP fields. Rebase onto the published model and rerun normal tests when it is available.

The design also adds `XAmznTraceId` to `DistributedMapOptions`. This Java SDK currently exposes no distributed-map
operation or START dispatch; its existing `map` and `parallel` APIs use CONTEXT operations. There is no new distributed
map/fanout/HTTP API here. The future generated model and high-level operation path must integrate their corresponding
field when introduced.

Before release, the generated model must be published and the backend must persist/forward the per-operation header.
Then validate deployed parent/child traces for durable and ordinary Lambda targets. Keep this PR draft until those
dependencies are ready; this remains related to issue #764. Runtime inbound headers and plugin-instance lifetime are
separate changes.

Tests exercise real public invoke and checkpoint paths, pending/terminal replay, failed uncommitted START recovery,
ordinary plugin fallbacks and Error propagation, sampled/unsampled OTel views, batched invokes with distinct parents,
custom payload and tenant preservation, generated-model copies, and the normal Lambda client's JSON marshaller with
only HTTP transport replaced. An isolated local model-preview fixture can test the assumed member shape, but cannot
establish that the public model or backend supports it.
