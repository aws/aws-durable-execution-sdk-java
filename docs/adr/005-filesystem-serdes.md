# ADR-005: Payload Offloading for Filesystem Storage

**Status:** Proposed  
**Date:** 2026-07-02

## Context

Issue [#463](https://github.com/aws/aws-durable-execution-sdk-java/issues/463) asks for Java parity with the JavaScript SDK's filesystem-backed SerDes. The JavaScript implementation receives a `SerdesContext` containing a stable durable execution ARN and an entity ID, then stores either inline JSON or a file pointer in the checkpoint payload. That context lets the implementation choose a collision-free path for each operation payload.

The Java SDK currently exposes a smaller `SerDes` contract:

```java
String serialize(Object value);
<T> T deserialize(String data, TypeToken<T> typeToken);
```

That contract is enough for inline JSON but not enough for external payload storage because the implementation cannot tell which durable execution, operation, payload kind, or exception it is handling. This is also the blocker noted in [#509](https://github.com/aws/aws-durable-execution-sdk-java/issues/509).

There are a few Java-specific constraints:

- `SerDes` is called from different threads today. Serialization usually happens on an operation worker thread, while deserialization can happen on the operation caller's context thread when `DurableFuture.get()` is called.
- The same operation payload can be deserialized multiple times in one invocation because most operation results are not cached after deserialization.
- Java uses the configured `SerDes` for both operation results and user-defined exception objects stored in `ErrorObject.errorData`.
- `DurableInputOutputSerDes` is a hard-coded internal serializer for the Lambda Durable Functions request and response envelope. It is separate from the customer-facing `DurableConfig.getSerDes()`.
- Filesystem-backed storage is optional and storage-specific. It should not add filesystem-oriented public surface area to the core SDK artifact.
- Filesystem persistence is not automatically durable. Lambda `/tmp` is not valid for replay across environments. Mounted S3 Files may have delayed synchronization and can lose recent writes if the runtime crashes before the mount flushes. EFS or an explicitly accepted S3 Files durability tradeoff should be required for production use.

## Shared Design Constraints

These constraints apply to both approaches below.

### Stable payload identity

Both approaches need a stable payload identity that can be used to address external storage. The identity must include the durable execution ARN, operation identity, payload kind, and enough operation metadata to distinguish result, input, callback, wait-for-condition state, and exception payloads.

`entityId` is the primary stable key for external storage. It must be unique within a durable execution and include the payload kind so one operation can safely store multiple values:

| Payload | Example entity ID |
|---------|-------------------|
| Root input | `execution/<execution-operation-id>/input` |
| Root output | `execution/<execution-operation-id>/output` |
| Root exception | `execution/<execution-operation-id>/exception` |
| Step result | `operation/<operation-id>/result` |
| Step exception | `operation/<operation-id>/exception` |
| Invoke payload | `operation/<operation-id>/invoke-payload` |
| Invoke result | `operation/<operation-id>/result` |
| Callback result | `operation/<operation-id>/result` |
| Child context result | `operation/<operation-id>/result` |
| Map result | `operation/<operation-id>/result` |
| WaitForCondition state | `operation/<operation-id>/state` |

Do not include the checkpoint token or raw user payload in the context.

### Extra package pattern

Payload offloading implementations should live outside the core SDK artifact when they target a specific storage mechanism.

Use the `aws-durable-execution-sdk-java-extra-xxx` artifact pattern:

| Feature | Artifact ID | Java package |
|---------|-------------|--------------|
| Filesystem SerDes | `aws-durable-execution-sdk-java-extra-filesystem-serdes` | `software.amazon.lambda.durable.extra.filesystem` |
| Filesystem offloader | `aws-durable-execution-sdk-java-extra-filesystem-offloader` | `software.amazon.lambda.durable.extra.filesystem` |
| Event deserialization helpers | `aws-durable-execution-sdk-java-extra-event-deserialization` | `software.amazon.lambda.durable.extra.eventdeserialization` |
| Virtual thread executor helpers | `aws-durable-execution-sdk-java-extra-virtual-thread-pool` | `software.amazon.lambda.durable.extra.virtualthreads` |

Extra modules should be independently documented, tested, and versioned with the repository release. They may depend on the core SDK and normal support libraries, but the core SDK should expose stable extension points without knowing about any specific extra package.

### Protocol SerDes boundary

Do not make `DurableInputOutputSerDes` customizable as part of this ADR. It serializes the Lambda Durable Functions backend protocol envelope, not user payloads. Routing that envelope through external payload storage would risk storing checkpoint tokens or protocol data externally and would require the backend to understand file pointers.

User input and output payloads should still use the configured user payload mechanism when they are extracted from or written to the execution operation. The internal `DurableExecutionInput` and `DurableExecutionOutput` envelope remains handled by `DurableInputOutputSerDes`.

If protocol customization is needed later, introduce a separate `ProtocolSerDes` configuration surface with a clear warning that it must produce the exact backend wire format. Do not reuse the user payload `SerDes` for that purpose.

## Approach A: Reuse SerDes for Offload

### Summary

Keep the existing `SerDes` contract unchanged and implement `FileSystemSerDes` as an optional extra package. The implementation uses `SerDesContext.getCurrentContext()` to identify the durable execution and entity being serialized.

```java
public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, TypeToken<T> typeToken);
}
```

`FileSystemSerDes` acts as both serializer and payload offloader. It serializes values through a delegate SerDes, writes payloads to the filesystem when configured to do so, and stores a small envelope in the checkpoint.

Because the existing `SerDes` methods do not accept context parameters, this approach needs a thread-local `SerDesContext` so `FileSystemSerDes` can discover the current payload identity without changing the `SerDes` interface.

```java
public record SerDesContext(
        String durableExecutionArn,
        String entityId,
        SerDesPayloadKind payloadKind,
        String operationId,
        String operationName,
        String parentId,
        OperationType operationType,
        OperationSubType operationSubType,
        Integer attempt) {
    public static SerDesContext getCurrentContext() {
        return SerDesContextHolder.getCurrentContext();
    }
}
```

The SDK owns setting and clearing this thread-local value around SDK-managed SerDes calls. The setter should not be part of the public customer API; customers only read the current context. If SerDes is called directly by customer code outside the SDK, `getCurrentContext()` returns `null`.

### Package

| Concern | Decision |
|---------|----------|
| Maven module directory | `extra-filesystem-serdes` |
| Maven artifact ID | `aws-durable-execution-sdk-java-extra-filesystem-serdes` |
| Maven group ID | `software.amazon.lambda.durable` |
| Java package | `software.amazon.lambda.durable.extra.filesystem` |
| Core dependency direction | Extra module depends on `aws-durable-execution-sdk-java`; core does not depend on extras. |

### Configuration

```java
import software.amazon.lambda.durable.extra.filesystem.FileSystemSerDes;

var serDes = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
        .storageMode(FileSystemStorageMode.ALWAYS)
        .pathEncoding(FileSystemPathEncoding.URI)
        .delegate(new JacksonSerDes())
        .previewGenerator(optionalPreviewGenerator)
        .build();

return DurableConfig.builder()
        .withSerDes(serDes)
        .build();
```

Storage modes:

| Mode | Behavior |
|------|----------|
| `ALWAYS` | Always write the delegate-serialized value to a file and store a file envelope in the checkpoint. |
| `OVERFLOW` | Store inline until the checkpoint envelope approaches the service payload limit, then write to a file. |

Path encodings:

| Encoding | Behavior |
|----------|----------|
| `URI` | Use readable, escaped path segments derived from durable execution ARN and entity ID. |
| `HASH` | Use SHA-256 names for fixed-length, filesystem-safe paths. |

Envelope format:

```json
{"data":"<inline delegate payload>"}
{"file":"<absolute path>"}
{"file":"<absolute path>","preview":{ "...": "..." }}
```

`FileSystemSerDes` must reject calls when `SerDesContext.getCurrentContext()` is `null` or does not include `durableExecutionArn` and `entityId`.

### Runtime flow

```java
SerDesContextHolder.set(context);
try {
    var checkpointPayload = fileSystemSerDes.serialize(value);
    sendCheckpoint(checkpointPayload);
} finally {
    SerDesContextHolder.clear();
}
```

On deserialization, `FileSystemSerDes` parses its envelope. If the envelope contains `data`, it delegates directly to the inner SerDes. If the envelope contains `file`, it reads file contents and delegates to the inner SerDes.

### Threading

Add a separate executor to `DurableConfig`:

```java
DurableConfig.builder()
        .withSerDesExecutorService(customSerDesExecutor)
        .build();
```

The default should be a cached daemon thread pool named `durable-sdk-serdes-*`.

The core SDK should route user payload SerDes calls through a helper, tentatively `SerDesRunner`, that:

- Builds the correct `SerDesContext`.
- Sets `SerDesContext` in TLS inside the SerDes executor task.
- Invokes the existing `SerDes.serialize` and `SerDes.deserialize` methods.
- Clears TLS after each SerDes call.
- Wraps failures in `SerDesException` with operation and payload kind metadata.

Because TLS is bound to a single Java thread, `SerDesRunner` must set `SerDesContext` inside the SerDes executor task before calling the user SerDes. It must not rely on inheritable thread-local propagation from the operation thread because cached pool threads can be reused across operations and invocations.

### Caching

Add an invocation-scoped cache for successful deserialization results. The cache key should include:

- Durable execution ARN.
- `entityId`.
- Payload kind.
- Target `TypeToken` type.
- A hash of the serialized checkpoint string.

The serialized string hash prevents stale results when a `WAIT_FOR_CONDITION` or retried step updates the same operation payload across attempts. Cache entries live only for the current Lambda invocation and are discarded when `ExecutionManager` closes.

With this approach, SDK caching can avoid repeated calls to `FileSystemSerDes.deserialize`. If a cache miss occurs, `FileSystemSerDes` may perform a file read internally.

### Exceptions

Keep the current `ErrorObject` shape:

- `errorType`: the Java exception class name.
- `errorMessage`: the exception message.
- `errorData`: the SerDes payload or file pointer for the exception object.
- `stackTrace`: SDK-serialized stack trace entries.

When serializing `errorData`, set `SerDesPayloadKind.EXCEPTION` and use an entity ID distinct from the operation result. When deserializing, continue to load `Class.forName(errorType)` and call SerDes with `TypeToken.get(exceptionClass.asSubclass(Throwable.class))`.

`FileSystemSerDes` does not own exception type reconstruction. It only stores and loads the exception JSON or file pointer. Reconstruction remains in `SerializableDurableOperation.deserializeException` and `DurableExecutor.buildErrorObject`.

### Input and output

Root user input and output payloads should route through `SerDesRunner` so `FileSystemSerDes` can see `SerDesContext`. The internal `DurableExecutionInput` and `DurableExecutionOutput` envelope stays with `DurableInputOutputSerDes`.

### Implementation plan

1. Add `SerDesContext`, `SerDesPayloadKind`, and package-private TLS setter/clearer support. Leave the `SerDes` interface unchanged.
2. Add `SerDesRunner` and a `SerDesExecutor` default pool. Add `DurableConfig.withSerDesExecutorService(...)` and validation.
3. Update root input/output handling in `DurableExecutor` to run user payload SerDes through `SerDesRunner` while leaving `DurableInputOutputSerDes` internal.
4. Update `SerializableDurableOperation`, `InvokeOperation`, `StepOperation`, `WaitForConditionOperation`, `CallbackOperation`, `ChildContextOperation`, `MapOperation`, and test helpers to use `SerDesRunner`.
5. Add invocation-scoped deserialization caching keyed by entity, payload kind, type, and serialized data hash.
6. Update exception serialization and deserialization paths to set `SerDesPayloadKind.EXCEPTION` in TLS.
7. Add the `extra-filesystem-serdes` Maven module with artifact ID `aws-durable-execution-sdk-java-extra-filesystem-serdes`, depending on the core SDK.
8. Implement `FileSystemSerDes` in `software.amazon.lambda.durable.extra.filesystem` with `ALWAYS` and `OVERFLOW` modes, `URI` and `HASH` path encodings, envelope parsing, atomic file writes where supported by the filesystem, and clear validation errors for missing context.
9. Add unit tests for context construction, unchanged `SerDes` compatibility, TLS scoping and clearing, thread-pool isolation, cache hits, cache invalidation when serialized data changes, exception reconstruction, malformed filesystem envelopes, and extra-module packaging.
10. Add integration tests with `LocalDurableTestRunner` for step results, wait-for-condition state, invoke payload/result, child context results, map results, repeated `get()`, replay from file pointers, and custom exception types.
11. Update README and advanced configuration docs with FileSystemSerDes dependency coordinates, FileSystemSerDes examples, and warnings about `/tmp`, S3 Files flush behavior, and EFS/S3 Files operational requirements.

### Pros

- Delivers the requested parity feature with the smallest new public API surface.
- Uses an extension point customers already understand and can configure per operation.
- Keeps the first implementation in an optional `aws-durable-execution-sdk-java-extra-*` module.
- Avoids committing the core SDK to a generalized offloading envelope before the storage use cases are proven.
- Closest to the current JavaScript `createFileSystemSerdes` model and issue #463 wording.

### Cons

- Uses serialization as a storage hook, so the name `SerDes` no longer means only object-to-string conversion.
- Forces customers who already have a custom SerDes to wrap or compose it with FileSystemSerDes.
- May lead to one-off storage SerDes implementations if S3, DynamoDB, or other backends are added later.
- Makes it harder for the SDK to reason separately about serialized text size, offloaded payload references, and storage lifecycle.
- The SDK treats the checkpoint envelope as opaque serialized data, so lifecycle and preview behavior are owned by the SerDes implementation.

## Approach B: Create a PayloadOffloader Interface

### Summary

Introduce a dedicated offloading abstraction in the core SDK. SerDes remains responsible only for object-to-string conversion. The offloader decides whether to keep serialized data inline or store it in third-party storage.

```java
public interface PayloadOffloader {
    OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context);

    String load(OffloadedPayload payload, PayloadOffloadContext context);
}
```

`OffloadedPayload` is an SDK-owned envelope model that can represent inline data, a storage reference, and optional preview data.

```java
public record OffloadedPayload(
        PayloadStorageMode mode,
        String data,
        String reference,
        Map<String, Object> preview) {}
```

`PayloadOffloadContext` carries the stable payload identity directly as an explicit method parameter:

```java
public record PayloadOffloadContext(
        String durableExecutionArn,
        String entityId,
        SerDesPayloadKind payloadKind,
        String operationId,
        String operationName,
        String parentId,
        OperationType operationType,
        OperationSubType operationSubType,
        Integer attempt) {}
```

### Package

The `PayloadOffloader` interface and SDK-owned envelope model belong in the core SDK because the core runtime must apply them uniformly to root input/output, operation results, invoke payloads, callback results, wait-for-condition state, and exception payloads.

Filesystem-specific implementation remains an extra package:

| Concern | Decision |
|---------|----------|
| Core API package | `software.amazon.lambda.durable.offload` |
| Extra Maven module directory | `extra-filesystem-offloader` |
| Extra Maven artifact ID | `aws-durable-execution-sdk-java-extra-filesystem-offloader` |
| Extra Java package | `software.amazon.lambda.durable.extra.filesystem` |
| Core dependency direction | Extra module depends on `aws-durable-execution-sdk-java`; core does not depend on extras. |

### Configuration

```java
import software.amazon.lambda.durable.extra.filesystem.FileSystemPayloadOffloader;

var offloader = FileSystemPayloadOffloader.builder(Path.of("/mnt/efs/durable-payloads"))
        .storageMode(PayloadOffloadMode.ALWAYS)
        .pathEncoding(FileSystemPathEncoding.URI)
        .previewGenerator(optionalPreviewGenerator)
        .build();

return DurableConfig.builder()
        .withSerDes(new JacksonSerDes())
        .withPayloadOffloader(offloader)
        .build();
```

Configuration needs a precedence model:

| Level | Behavior |
|-------|----------|
| Global `DurableConfig.withPayloadOffloader(...)` | Applies to all user payloads unless operation config overrides it. |
| Operation config offloader | Overrides the global offloader for a step, invoke, callback, child context, map, or wait-for-condition operation. |
| Disabled offloader | Forces inline payload storage for payloads where external storage is not desired. |

SerDes selection remains independent:

- `SerDes` converts objects to and from serialized text.
- `PayloadOffloader` converts serialized text to and from checkpoint-safe inline data or storage references.

### Runtime flow

```java
var serialized = serDes.serialize(value);
var offloaded = payloadOffloader.offload(serialized, offloadContext);
var checkpointPayload = offloadEnvelopeSerDes.serialize(offloaded);
sendCheckpoint(checkpointPayload);
```

On replay:

```java
var offloaded = offloadEnvelopeSerDes.deserialize(checkpointPayload, OffloadedPayload.class);
var serialized = payloadOffloader.load(offloaded, offloadContext);
var value = serDes.deserialize(serialized, typeToken);
```

The SDK owns the checkpoint/offload envelope. Storage implementations own only the storage reference and the read/write mechanics.

### Threading

Use a separate executor for blocking payload I/O. This can be the same configured executor as SerDes work or a distinct executor if the team wants independent tuning:

```java
DurableConfig.builder()
        .withSerDesExecutorService(customSerDesExecutor)
        .withPayloadOffloadExecutorService(customOffloadExecutor)
        .build();
```

If a single executor is preferred, name it according to the broader responsibility, for example `durable-sdk-payload-*`.

Because filesystem/S3/DynamoDB offloading can block, offload work should not run on the user operation executor or the SDK internal executor.

### Caching

The SDK can cache at two layers:

| Cache | Key | Value |
|-------|-----|-------|
| Offloaded payload cache | Durable execution ARN, entity ID, payload kind, checkpoint payload hash | Resolved serialized text |
| Deserialized object cache | Durable execution ARN, entity ID, payload kind, target type, serialized text hash | Deserialized object |

This lets the SDK avoid repeated file reads and repeated object reconstruction independently. It is also easier for tests and diagnostics because the SDK can observe whether a checkpoint payload is inline or externally referenced.

### Exceptions

Exception handling becomes uniform. The SDK first serializes the exception object with SerDes, then offloads the resulting `errorData` just like any other payload.

The `ErrorObject` shape can remain the same if `errorData` stores the SDK-owned offload envelope:

- `errorType`: the Java exception class name.
- `errorMessage`: the exception message.
- `errorData`: inline serialized exception data or an SDK-owned offload envelope.
- `stackTrace`: SDK-serialized stack trace entries.

Reconstruction remains in `SerializableDurableOperation.deserializeException` and `DurableExecutor.buildErrorObject`, but those paths must first resolve the offloaded `errorData` before calling SerDes.

### Input and output

Root user input and output payloads should use the same SerDes-plus-offloader pipeline. The internal `DurableExecutionInput` and `DurableExecutionOutput` envelope stays with `DurableInputOutputSerDes`.

This approach gives the SDK one consistent policy for root payloads, operation results, invoke payloads, callbacks, wait-for-condition state, map results, and exception payloads.

### Implementation plan

1. Add `SerDesPayloadKind` and a shared payload identity builder that can create `PayloadOffloadContext` for root input/output, operation results, invoke payloads, callback results, wait-for-condition state, map results, and exception payloads.
2. Add `PayloadOffloader`, `PayloadOffloadContext`, `OffloadedPayload`, and an SDK-owned offload envelope serializer in the core SDK.
3. Add `DurableConfig.withPayloadOffloader(...)` and optional operation-level offloader configuration.
4. Define precedence rules between global offloader, operation offloader, disabled offloader, result SerDes, payload SerDes, and callback deserializers.
5. Add a payload pipeline helper, tentatively `PayloadCodec`, that composes SerDes, offload, caching, executor routing, and exception wrapping.
6. Update root input/output handling in `DurableExecutor` to use the payload pipeline while leaving `DurableInputOutputSerDes` internal.
7. Update all operation result, invoke payload, callback result, wait-for-condition state, child context, map, and exception paths to use the payload pipeline.
8. Add offloaded payload caching and deserialized object caching.
9. Add the `extra-filesystem-offloader` Maven module with artifact ID `aws-durable-execution-sdk-java-extra-filesystem-offloader`, depending on the core SDK.
10. Implement `FileSystemPayloadOffloader` in `software.amazon.lambda.durable.extra.filesystem` with `ALWAYS` and `OVERFLOW` modes, `URI` and `HASH` path encodings, atomic file writes where supported by the filesystem, and clear validation errors for missing context.
11. Add unit tests for offload envelope compatibility, precedence rules, thread-pool isolation, cache hits, cache invalidation, exception reconstruction, malformed references, and extra-module packaging.
12. Add integration tests with `LocalDurableTestRunner` for step results, wait-for-condition state, invoke payload/result, child context results, map results, repeated `get()`, replay from external references, and custom exception types.
13. Update README and advanced configuration docs with offloader dependency coordinates, filesystem offloader examples, and warnings about `/tmp`, S3 Files flush behavior, and EFS/S3 Files operational requirements.

### Pros

- Cleaner separation between object encoding and payload storage.
- Storage offloading works with any SerDes implementation without replacing it.
- Gives the SDK one place to enforce checkpoint envelope format, thresholds, previews, caching, and validation.
- Scales naturally to more backends and policies if third-party payload storage becomes a first-class feature.
- Lets the SDK cache resolved serialized text separately from deserialized objects.
- Makes exception, callback, invoke, root input/output, and operation result offloading more uniform.

### Cons

- Requires a new core SDK extension point and configuration model.
- Needs careful interaction rules with operation-level SerDes, payload SerDes, callback deserializers, test helpers, and error serialization.
- Requires a migration story for existing custom SerDes implementations that already return external references.
- Slows direct FileSystemSerDes parity while the broader offloading API is designed and stabilized.
- Diverges from the JavaScript `createFileSystemSerdes` naming and shape, even if the behavior is similar.
- Adds more core SDK responsibility because the runtime now owns the offload envelope.

## Approach Comparison

| Dimension | Approach A: Reuse SerDes | Approach B: PayloadOffloader |
|-----------|--------------------------|------------------------------|
| Responsibility boundary | Combines value serialization and storage-reference creation in one implementation. | Keeps object encoding in `SerDes` and storage movement in a separate offloader. |
| User configuration | Users replace or wrap their SerDes with `FileSystemSerDes`. Operation-level SerDes selection already exists. | Users configure both a SerDes and an offloader. The SDK must define global, per-operation, and per-payload precedence. |
| Parity with JS issue | Closest to the current JavaScript `createFileSystemSerdes` model and issue #463 wording. | Diverges from JavaScript naming and shape, though it may be architecturally cleaner for Java. |
| Core SDK changes | Requires `SerDesContext` TLS because the existing SerDes contract has no context parameter. | Requires a new core extension point, envelope type, config surface, payload pipeline, and migration story. |
| Applicability | Only payloads using the filesystem SerDes are offloaded. Other SerDes implementations must implement their own offload behavior or be wrapped. | Any SerDes output can be offloaded uniformly after serialization. Users can combine Jackson/custom SerDes with any offloader. |
| Envelope ownership | FileSystemSerDes owns the checkpoint envelope (`data`, `file`, preview), so the SDK treats it as opaque serialized data. | SDK owns the checkpoint/offload envelope and must guarantee it composes with replay, errors, callbacks, and test utilities. |
| Caching | SDK can cache deserialized values, but FileSystemSerDes may still do file reads internally unless cache hits happen before SerDes. | SDK can cache at both layers: resolved offloaded payload text and final deserialized object. |
| Exception handling | Works if every exception serialization path is routed through SerDes with `SerDesPayloadKind.EXCEPTION`. | Works uniformly because exception `errorData` is another serialized payload that can be offloaded after SerDes. |
| Third-party storage | Filesystem-specific; S3/DynamoDB would likely become more SerDes wrappers or extra packages. | Natural home for multiple storage backends: filesystem, S3, DynamoDB, EFS, S3 Files, or custom customer storage. |
| Immediate delivery risk | Lower. Builds on existing customization point. | Higher. Requires new API and more runtime integration. |
| Long-term design risk | Higher. Blurs SerDes semantics and may accumulate storage behavior in serializers. | Lower if offloading grows into a first-class feature, but higher if this remains a one-off filesystem parity feature. |

## AI Recommendation

**AI recommendation:** Prefer **Approach B: Create a `PayloadOffloader` interface** if the team is willing to treat payload offloading as a first-class Java SDK capability rather than only a JavaScript parity item.

Reasoning:

- The problem being solved is payload storage, not serialization. A dedicated offloader keeps the domain boundary clean.
- The SDK already needs to touch every payload path for context, caching, threading, exceptions, and root input/output. Once that plumbing exists, composing SerDes plus offloader is a more durable shape than putting storage behavior inside SerDes.
- Java customers are more likely to have custom Jackson/ObjectMapper SerDes implementations. Approach B lets them keep those and add offloading independently.
- Both approaches use optional extra packages for filesystem-specific code; that is not a differentiator. The differentiator is that Approach B gives future storage extras such as S3 or DynamoDB offload the same focused core offloader contract instead of encoding storage behavior as more SerDes implementations.
- SDK-owned envelopes and two-layer caching make replay behavior easier to test and reason about.

The main reason to choose Approach A is schedule and parity: it is smaller and maps directly to the JavaScript feature request. If the team needs to satisfy #463 quickly with minimal public API design, Approach A is a reasonable incremental step, but it should be documented as payload offloading implemented through SerDes rather than as the long-term ideal boundary.

## Other Alternatives Considered

### Add FileSystemSerDes without SerDesContext

Rejected. A filesystem-backed implementation needs stable operation identity. Without context, it cannot choose a safe file name, distinguish result and exception payloads for the same operation, or avoid collisions across durable executions.

### Put filesystem-backed offloading in the core SDK artifact

Rejected. Filesystem-backed storage is optional, storage-specific functionality. Keeping it in an `aws-durable-execution-sdk-java-extra-*` artifact preserves a small core SDK and creates a repeatable package shape for future optional features.

### Add context-aware SerDes overloads

Rejected for Approach A. Explicit overloads are more discoverable, but they expand the public `SerDes` interface and force context into every custom implementation's method surface. Approach A uses `SerDesContext` TLS only to keep the existing `SerDes` contract unchanged. Approach B does not need SerDes TLS because `PayloadOffloader` receives `PayloadOffloadContext` explicitly.

### Make SerDes async

Deferred. The TypeScript SDK uses async SerDes because file and service I/O are naturally async in Node.js. Java can isolate blocking work with dedicated executors while preserving synchronous user-facing interfaces. A future major version can revisit `CompletionStage<String>` and `CompletionStage<T>` if there is a stronger need.

### Run payload storage on the user executor

Rejected. Filesystem-backed storage can block on mounted storage. Running that work on the user executor can starve user operation threads and make unrelated steps appear stuck.

### Run payload storage on the internal SDK executor

Rejected. The internal executor is for checkpointing, polling, and coordination. Blocking storage work should not compete with progress-making SDK tasks.

### Cache inside the filesystem implementation only

Rejected. The repeated-deserialization problem exists for every payload implementation. SDK-level caching also lets the cache key include operation metadata, target type, and the serialized checkpoint string.

### Make DurableInputOutputSerDes user-configurable

Rejected. The backend request/response envelope is protocol data. User payload customization should happen at the user payload boundary, not at the protocol envelope boundary.

## Consequences

Positive:

- Both approaches enable filesystem-backed payload storage without changing the existing `SerDes` interface.
- Filesystem-specific functionality stays out of the core SDK artifact.
- The repository gets a repeatable `aws-durable-execution-sdk-java-extra-xxx` artifact pattern for optional packages.
- Custom payload implementations get enough context to use external storage safely.
- Blocking payload work is isolated from user operation and SDK coordination threads.
- Repeated file reads and repeated object reconstruction can be reduced within an invocation.
- User exception type reconstruction remains supported.

Negative:

- Adds executor, context, and caching machinery that must stay deterministic.
- Adds at least one Maven module and published artifact to release and document.
- Approach A requires thread-local SerDes context because the existing `SerDes` methods do not accept context.
- Repeated `get()` calls may return the same object instance in one invocation.
- Filesystem-backed storage introduces operational durability requirements outside the SDK's control.
- Approach A risks overloading the meaning of SerDes.
- Approach B requires a larger core SDK design before delivering filesystem parity.

Deferred:

- Choosing whether payload offloading is a first-class SDK concept or a parity feature implemented through SerDes.
- A fully async Java SerDes or payload pipeline contract.
- A separate, explicitly dangerous protocol-envelope customization API.
- File cleanup, retention policies, and lifecycle management for offloaded payloads.
