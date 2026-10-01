# ADR-005: Filesystem serialization for operation payloads

**Status:** Proposed for implementation with #463

**Date:** 2026-10-01

## Context

Issue [#463](https://github.com/aws/aws-durable-execution-sdk-java/issues/463)
requests filesystem SerDes parity. JavaScript and Python serialize values into
inline/file envelopes. The [.NET implementation](https://github.com/aws/aws-lambda-dotnet/pull/2558)
composes an inner serializer and accepts explicit durable execution and entity
identity through per-operation serializer slots.

Java's original `SerDes` methods do not convey operation identity. Earlier versions
of this ADR compared thread-local context and a separate payload offloader. This
decision selects the explicit-context model used by .NET, with Java default methods
to keep existing serializer implementations source and binary compatible.

## Decision

Add `SerDesContext(durableExecutionArn, entityId)` and default context-aware
`serialize` and `deserialize` overloads. The defaults call the original methods.
The runtime supplies context directly for operation results, exception data, and
invoke payloads, on both first execution and replay. It does not use thread-local
state or require serializers to adopt a new executor or cache.

Entity IDs are `operation/<operation-id>/result`,
`operation/<operation-id>/exception`, and
`operation/<operation-id>/invoke-payload`. Polling state shares the result identity;
retries and polling can serialize more than one value for an identity. External
callback/invoke results can have a different producer identity from their consumer.
References must therefore be self-contained and remain readable after later writes.

Include `FileSystemSerDes` and its storage/path options in the main
`aws-durable-execution-sdk-java` artifact under
`software.amazon.lambda.durable.serde`, alongside the existing serializers.
The implementation uses only the JDK and Jackson already required by the SDK,
so a separate artifact would add packaging and release overhead without reducing
dependencies.

The filesystem serializer wraps a configurable delegate, supports `ALWAYS` and
`OVERFLOW` modes, URI/hash paths, and optional custom previews. Overflow measures
the complete inline UTF-8 JSON envelope against 255 KiB. File/preview envelopes
must meet the same limit. Null serialized values remain null.

Every write uses a unique immutable file under the execution directory. The write
and local flush must succeed before its pointer is returned. This preserves older
checkpoints if a subsequent polling/retry write succeeds but its checkpoint does
not. Ordinary exceptions trigger cleanup of unpublished files; crashes may leave
orphans. No successful payload file is deleted automatically.

Filesystem SerDes is configured per operation, matching .NET. Context-free calls
fail clearly. Global Lambda input/output and the internal durable protocol envelope
retain their existing serialization paths. Callback and invoke users must explicitly
arrange compatible envelopes and shared storage on both sides of those boundaries.

## Consequences

- Existing custom serializers continue using their original methods by default.
- Custom serializers can use stable identities without relying on worker/caller
  thread affinity. No inheritance-based thread-local propagation is needed.
- Filesystem I/O stays synchronous, consistent with the existing SerDes contract.
  Delegate encoding still materializes a string in memory.
- Immutable files preserve replay but require external retention and cleanup.
- Mounted storage must survive execution-environment replacement. `/tmp` cannot
  do this; delayed mount synchronization can also lose recent writes.
- The application controls the mount. Path confinement and symlink checks protect
  against malformed references but do not sandbox another process with mount access.
- Filesystem serialization uses the existing SDK build, coverage, and publication.
  Unit tests live in `sdk`; local-runner integration tests live in
  `sdk-integration-tests`. No module or third-party library is added.

## Alternatives

A thread-local context keeps the SerDes method list unchanged but hides required
identity and creates thread-lifetime concerns. Default overloads make that identity
explicit while preserving original implementations.

A separate `PayloadOffloader`, dedicated executors, deserialization caches, and
serializer retry decorators may be useful independently. They are not required for
the per-operation filesystem feature and would expand its API and runtime scope.

Overwriting one file per entity uses less storage but can corrupt an older
checkpoint when a write succeeds and checkpoint publication fails. Immutable
references are required instead.

See the [filesystem serialization guide](../advanced/filesystem-serdes.md) for
configuration, wire format, storage lifetime, and validation.
