# Chained-invoke propagation groundwork

This draft prepares a plugin contract and pure OTel producers. It does **not** send propagation metadata or complete
chained-invoke propagation. Current public Lambda client models do not expose `ChainedInvokeOptions.XAmznTraceId`
or `DistributedMapOptions`. Production invoke START requests do not call this hook, and no serializer or raw HTTP
workaround is included.

`PropagationInput` is an immutable SDK-owned snapshot with `executionArn`, `operationId`, optional `parentOperationId`,
and `targetFunctionName`. `PropagationMetadata` is an immutable SDK-owned contribution with optional `xAmznTraceId`.
Both types use only Java strings; generated Lambda and OTel model types do not enter the plugin interface.
The typed default hook adapts a String-only producer overload, so bundled plugin layers do not reference newly added
core classes. Older cores continue to load and run those layers; new metadata collection requires a newer core,
without changing registration/provider API versions or rejecting previously working plugins.

The optional synchronous `providePropagationMetadata` default method returns null. Existing plugins keep compiling
and linking. The dispatcher supplies the same immutable snapshot in configured order. The first non-null supported
member wins; equal values do not conflict. Different later values warn with the winning and conflicting plugin
identities and a conflict count, without logging the header. Null contributions abstain; blank headers and ordinary
hook failures are logged and skipped. Java's return type rules out asynchronous and foreign result objects.
The collector preserves the current event dispatch policy: `Exception` (including `CancellationException`) is
contained, while `Error` propagates. A failing logging backend does not interrupt collection.

Both OTel views encode their resolved canonical trace ID, operation span ID and sampling decision as
`Root=1-<8 hex>-<24 hex>;Parent=<16 hex>;Sampled=0|1`. They require an active invocation with matching execution
ownership. An observed operation's context is authoritative, including an invocation-view continuation segment.
Before a new operation starts, the producer derives the same initial-operation ID the normal start hook will use.
It creates no spans, samples nothing again, and does not alter the existing provider, resource, upstream-parent or
fallback behavior. An explicit upstream `Sampled=0` still produces metadata with `Sampled=0`.

Remaining work before this can implement the feature:

1. Supported public client models and generated serialization for `ChainedInvokeOptions.XAmznTraceId` and
   `DistributedMapOptions`.
2. Backend rollout of the reviewed flat X-Ray header contract.
3. Consuming the collector on the real operation START path, including replay and failed-checkpoint integration.
4. Deployed trace topology and propagation validation.

Keep the implementation draft while these dependencies block completion. This is related to Java issue #764;
it does not close that issue. Runtime inbound headers and plugin-instance lifetime remain separate changes.
