# ADR-005: Payload Offloading for Filesystem Storage

**Status:** Accepted — Approach A with ComposableSerDes pipeline
**Date:** 2026-07-02
**Updated:** 2026-08-25 — Included FileSystemSerDes in the core SDK artifact.

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
- The filesystem implementation uses JDK filesystem APIs and existing core dependencies. Including the initial
  implementation in the core SDK avoids a second artifact and release path for the accepted parity feature.
- Filesystem persistence is not automatically durable. Lambda `/tmp` is not valid for replay across environments. Mounted S3 Files may have delayed synchronization and can lose recent writes if the runtime crashes before the mount flushes. EFS or an explicitly accepted S3 Files durability tradeoff should be required for production use.

## Approach A: Reuse SerDes for Offload

### Summary

Keep the existing `SerDes` serialization methods source- and binary-compatible, add a default composition method and a
core `ComposableSerDes` implementation which together chain multiple `SerDes` instances into a processing pipeline,
and implement `FileSystemSerDes` in the core SDK. The filesystem stage uses
`SerDesContext.getCurrentContext()` to identify the durable execution and entity being serialized.

```java
public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, TypeToken<T> typeToken);

    default ComposableSerDes then(SerDes nextStage) {
        return ComposableSerDes.of(this, nextStage);
    }
}
```

`ComposableSerDes` treats the first stage as the value codec and every later stage as a reversible string
transformation. This lets customers compose JSON encoding, compression, encryption, filesystem storage, or other
processing without each implementation needing to know about every other concern.

`FileSystemSerDes` acts as a payload-storage stage. It writes the string produced by the previous stage to the
filesystem when configured to do so and returns a small envelope for the next stage or checkpoint. For standalone
compatibility, it may still be constructed with a value-encoding delegate; pipeline configuration is the preferred
composition model.

Because the existing `SerDes` methods do not accept context parameters, this approach needs a thread-local
`SerDesContext` so `FileSystemSerDes` can discover the current payload identity without changing the
`serialize`/`deserialize` signatures.

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
        return SerDesContextHolder.get();
    }
}
```

The SDK owns setting and clearing this thread-local value around SDK-managed SerDes calls. The setter should not be part of the public customer API; customers only read the current context. If SerDes is called directly by customer code outside the SDK, `getCurrentContext()` returns `null`.

### Package

| Concern | Decision |
|---------|----------|
| Maven module directory | `sdk` |
| Maven artifact ID | `aws-durable-execution-sdk-java` |
| Maven group ID | `software.amazon.lambda.durable` |
| Java package | `software.amazon.lambda.durable.serde` |
| Dependency impact | No additional artifact or production dependency is required. |

### Configuration

```java
import software.amazon.lambda.durable.serde.FileSystemPathEncoding;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.FileSystemStorageMode;
import software.amazon.lambda.durable.serde.JacksonSerDes;

var fileSystemStage = FileSystemSerDes.stageBuilder(Path.of("/mnt/efs/durable-payloads"))
        .storageMode(FileSystemStorageMode.ALWAYS)
        .pathEncoding(FileSystemPathEncoding.URI)
        .previewGenerator(optionalPreviewGenerator)
        .build();

var serDes = new JacksonSerDes().then(fileSystemStage);

return DurableConfig.builder()
        .withSerDes(serDes)
        .withSerDesExecutorService(customSerDesExecutor)
        .build();
```

### Composable SerDes pipeline

`ComposableSerDes` is a core implementation of `SerDes`. It owns an immutable, ordered list of stages while preserving
the existing `serialize` and `deserialize` methods:

```java
public final class ComposableSerDes implements SerDes {
    public static ComposableSerDes of(SerDes first, SerDes... remaining);

    public static Builder builder(SerDes valueCodec);

    public SerDes getValueCodec();

    public ComposableSerDes then(SerDes stage);

    public static final class Builder {
        public Builder then(SerDes stage);

        public ComposableSerDes build();
    }
}

public record SerDesStageResult(String value, boolean skipRemainingStages) {
    public static SerDesStageResult continueWith(String value);

    public static SerDesStageResult decodeWithValueCodec(String value);
}
```

The first stage is the **value codec**. It converts the user value to a string and converts the final decoded string
back to the requested `TypeToken<T>`. Every later stage is a **string stage**: it must accept a `String` in
`serialize(Object)` and must return a `String` when `deserialize` is called with `TypeToken.get(String.class)`.

Serialization runs from first to last:

```text
Object
  -> value codec
  -> String stage 1
  -> String stage 2
  -> ...
  -> checkpoint String
```

Deserialization runs in the opposite direction:

```text
checkpoint String
  -> last string stage, deserialized as String
  -> ...
  -> first string stage, deserialized as String
  -> value codec, deserialized as the requested TypeToken<T>
  -> T
```

Equivalent pseudocode:

```java
String serialize(Object value) {
    String current = stages.get(0).serialize(value);
    for (int i = 1; i < stages.size(); i++) {
        current = stages.get(i).serialize(current);
    }
    return current;
}

<T> T deserialize(String data, TypeToken<T> targetType) {
    String current = data;
    for (int i = stages.size() - 1; i > 0; i--) {
        var decoded = stages.get(i).deserializePipelineStage(current);
        current = decoded.value();
        if (decoded.skipRemainingStages()) {
            break;
        }
    }
    return stages.get(0).deserialize(current, targetType);
}
```

Pipeline rules:

- A pipeline must contain exactly one value codec in the first position and zero or more string stages.
- A stage may declare that it requires durable context or that it must be terminal. A terminal stage must be the last
  stage so later transformations cannot invalidate its checkpoint-size or storage decision.
- `SerDes.then(...)`, `ComposableSerDes.of(...)`, and the builder flatten nested `ComposableSerDes` instances while
  preserving stage order.
- A `null` value at the pipeline boundary short-circuits the entire pipeline: serializing or deserializing `null`
  returns `null` without invoking any stage. A string stage returning `null` for non-null input is an error. The value
  codec may decode a non-null representation such as the JSON literal `null` to a null domain value.
- All stages execute within the same `SerDesRunner` invocation and observe the same read-only `SerDesContext`, whether
  the runner executes inline or dispatches to a configured executor.
- `ComposableSerDes` is immutable. It is safe for concurrent use only when every contained stage is also safe for
  concurrent use, matching the existing `SerDes` requirement.
- A failure must identify the stage index and implementation class in `SerDesException`; `SerDesRunner` adds durable
  entity and payload-kind metadata around the pipeline failure.
- A string stage must be reversible. Compression, encryption, signing envelopes, and external payload storage are
  suitable stages; lossy redaction is not.
- A boundary stage may return `SerDesStageResult.decodeWithValueCodec(...)` when it receives raw data that did not pass
  through the configured pipeline. `ComposableSerDes` then skips every earlier string stage and decodes the raw value
  directly with the value codec.
- Stage order is meaningful. For example, `JSON -> compression -> encryption -> filesystem` writes encrypted,
  compressed data to the filesystem. `FileSystemSerDes` is terminal; placing encryption or any expanding
  transformation after it is rejected.
- The ordered stage list and each stage's configuration are part of the persisted checkpoint format. They must remain
  replay-compatible for in-flight executions. Reordering, removing, or incompatibly reconfiguring a stage requires a
  versioned envelope or an explicit migration boundary.
- `ComposableSerDes` does not add a generic pipeline envelope or persist stage names. Stages that need format
  evolution must version their own output.
- Global and operation-level SerDes selection continues to select one `SerDes` instance. A `ComposableSerDes` is
  treated as that single instance; operation-level selection replaces the whole pipeline rather than merging stages.
- Invocation-scoped caching wraps the complete pipeline. Cache keys use the final checkpoint string and target type, so
  cache hits skip every reverse-processing stage, including filesystem reads.

The default `SerDes.then(...)` method and immutable `ComposableSerDes.then(...)` method provide a concise form for
independently reusable processing chains:

```java
var securePayloads = new JacksonSerDes()
        .then(compressionSerDes)
        .then(encryptionSerDes)
        .then(fileSystemStage);
```

### Retryable SerDes stages

Transient failures are explicit. `RetryableSerDesException` extends `SerDesException` and marks a failure that may
succeed when attempted again. `RetrySerDes` decorates another SerDes instance and applies an existing `RetryStrategy`:

```java
var resilientFileSystemStage = new RetrySerDes(
        fileSystemStage,
        RetryStrategies.exponentialBackoff(
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                2.0,
                JitterStrategy.FULL));

var serDes = new JacksonSerDes().then(resilientFileSystemStage);
```

Retry rules:

- Only `RetryableSerDesException` is retried. Ordinary `SerDesException` and other failures propagate immediately.
- `RetryStrategy.makeRetryDecision(error, attempt)` receives the transient failure and a 1-based attempt number.
- When the strategy returns `fail`, `RetrySerDes` rethrows the last `RetryableSerDesException`.
- The same read-only `SerDesContext` remains installed for every attempt because retrying happens inside the original
  `SerDesRunner` task.
- A retry delay blocks the thread executing the SerDes call. This is the caller thread by default or a SerDes executor
  thread when one is explicitly configured. It is an in-invocation infrastructure retry, not a durable wait or
  checkpoint. Strategies must therefore use short, bounded delays that fit within the Lambda invocation timeout.
- If the invocation is interrupted or times out, replay may execute the SerDes pipeline again. Any stage with side
  effects must use stable addressing and idempotent writes.
- `RetrySerDes` can wrap an individual stage or the complete pipeline. Wrapping the smallest transient stage avoids
  repeating deterministic encoding, compression, or encryption work.
- Pipeline error decoration must preserve retryability: a stage-level `RetryableSerDesException` must remain that type,
  with stage metadata added to its message or cause, so an enclosing `RetrySerDes` can recognize it.
- Filesystem read and write `IOException`s are retryable. Malformed envelopes, invalid paths, unsupported types, and
  delegate encoding errors are permanent.

Storage modes:

| Mode | Behavior |
|------|----------|
| `ALWAYS` | Always write the incoming stage string to a file and return a file envelope. |
| `OVERFLOW` | Return an inline envelope until it approaches the service payload limit, then write the incoming stage string to a file. |

Path encodings:

| Encoding | Behavior |
|----------|----------|
| `URI` | Use readable, escaped path segments derived from durable execution ARN and entity ID. |
| `HASH` | Use SHA-256 names for fixed-length, filesystem-safe paths. |

Envelope format:

```json
{"__durable_execution_filesystem_serdes":1,"data":"<inline stage input>","ownerDurableExecutionArn":"<producer ARN>","ownerEntityId":"<producer entity>"}
{"__durable_execution_filesystem_serdes":1,"file":"<absolute path>","ownerDurableExecutionArn":"<producer ARN>","ownerEntityId":"<producer entity>"}
{"__durable_execution_filesystem_serdes":1,"file":"<absolute path>","ownerDurableExecutionArn":"<producer ARN>","ownerEntityId":"<producer entity>","preview":{ "...": "..." }}
```

`FileSystemSerDes` must reject calls when `SerDesContext.getCurrentContext()` is `null` or does not include
`durableExecutionArn` and `entityId`. When used as a pipeline stage, it must also reject non-string input or a
deserialization target other than `String`.

The marker and version distinguish filesystem envelopes from raw service-originated JSON. Initial root input, callback
results, and standard Lambda invoke results may arrive before this SerDes has processed them. For those external
payload sources only, an input without the filesystem marker is decoded directly with the pipeline value codec or
standalone delegate. Skipping every string stage is required because raw external data has not been compressed,
encrypted, or otherwise transformed by those stages. Missing or malformed markers on SDK-checkpointed payloads are
permanent errors. The marker name is reserved: malformed marked envelopes and unsupported envelope versions are
rejected at external boundaries rather than being treated as raw user data.

Offloaded filenames include a content hash and are immutable. Serializing new state for the same entity creates a new
path instead of replacing a file referenced by an earlier checkpoint. Repeating the same serialization may reuse the
same content-addressed file.

File envelopes identify the execution ARN and entity that produced the content. Normal checkpoint replay requires
that owner to match the current context. Initial input and chained-invoke result boundaries may consume a reference
owned by the other Lambda execution, allowing two functions configured with the same durable filesystem root and path
encoding to exchange offloaded invoke payloads and results. The declared owner must still match the content-addressed
path, and the resolved file must remain beneath the configured root. The file envelope is therefore a capability and
must be protected with the same care as the payload it references.

The final file envelope, including any preview, must remain below the checkpoint threshold. Oversized previews are
rejected rather than producing a checkpoint that the service cannot accept.

`FileSystemSerDes` declares itself terminal in every mode. This makes its overflow decision apply to the final
checkpoint representation and prevents a later Base64, encryption, or other expanding stage from pushing an inline
envelope over the service limit.

In stage mode, the preview generator receives the string produced by the preceding stage, not the original domain
object. A preview that needs domain fields should either parse that representation, be produced by an earlier stage, or
use standalone compatibility mode where `FileSystemSerDes` receives the original value.

### Runtime flow

```java
var previousContext = SerDesContextHolder.get();
SerDesContextHolder.set(context);
try {
    var checkpointPayload = composableSerDes.serialize(value);
    sendCheckpoint(checkpointPayload);
} finally {
    if (previousContext == null) {
        SerDesContextHolder.clear();
    } else {
        SerDesContextHolder.set(previousContext);
    }
}
```

On deserialization, `FileSystemSerDes` parses its envelope. If the envelope contains `data`, it returns the inline
string. If the envelope contains `file`, it reads and returns the file contents. `ComposableSerDes` then passes that
string to the preceding stage. In standalone compatibility mode, `FileSystemSerDes` instead passes the resolved string
to its configured value-encoding delegate. Raw external input, callback results, and standard invoke results skip all
string stages and go directly to the value codec when no versioned filesystem marker is present.

### Threading

Preserve the current SDK behavior by executing SerDes inline on the calling thread by default. Do not create a default
SerDes thread pool. This avoids a queue operation, `CompletableFuture` allocation, and thread hop for ordinary in-memory
serialization such as `JacksonSerDes`.

Customers can explicitly configure a separate executor when a SerDes performs blocking I/O or retry backoff:

```java
DurableConfig.builder()
        .withSerDesExecutorService(customSerDesExecutor)
        .build();
```

If `withSerDesExecutorService(...)` is not called, the configured executor is absent and `SerDesRunner` invokes the
pipeline synchronously on the current thread. The builder method accepts only a non-null executor; not calling it is
how customers select inline execution. If an executor is configured, `SerDesRunner` dispatches the complete pipeline
to that executor and waits for its result. Configuration rejects using the same executor instance for user operations
and SerDes because synchronous dispatch to a saturated shared pool can deadlock.

The core SDK should route user payload SerDes calls through a helper, tentatively `SerDesRunner`, that:

- Builds the correct `SerDesContext`.
- Executes inline when no SerDes executor is configured.
- Dispatches to the configured executor only when one is present.
- Sets `SerDesContext` in TLS on the thread that actually invokes the SerDes.
- Invokes the existing `SerDes.serialize` and `SerDes.deserialize` methods.
- Restores the previous TLS value after each SerDes call, or clears it when there was no previous value.
- Wraps failures in `SerDesException` with operation and payload kind metadata.

Equivalent execution flow:

```java
T run(SerDesContext context, Supplier<T> operation) {
    if (serDesExecutorService == null) {
        return runWithContext(context, operation);
    }
    return CompletableFuture
            .supplyAsync(() -> runWithContext(context, operation), serDesExecutorService)
            .join();
}
```

Because TLS is bound to a single Java thread, `SerDesRunner` must install the context on whichever thread executes the
operation. It must not rely on inheritable thread-local propagation. Restoring the previous value also makes nested
SDK-managed SerDes calls safe in inline mode.

Inline execution is the compatibility and low-overhead default, not a recommendation to perform blocking storage work
on operation threads. Documentation and filesystem examples should configure a SerDes executor whenever
`FileSystemSerDes`, delayed `RetrySerDes`, or another blocking stage is used. If it is omitted, I/O and retry delays
block the calling thread.

### Caching

Add an invocation-scoped cache for successful deserialization results. The cache key should include:

- The identity of the SerDes instance.
- Durable execution ARN.
- `entityId`.
- Payload kind.
- Attempt, when applicable.
- Target `TypeToken` type.
- A hash of the serialized checkpoint string.

The SerDes identity prevents two configured pipelines from sharing a call-order-dependent result. The serialized string
hash prevents stale results when a `WAIT_FOR_CONDITION` or retried step updates the same operation payload across
attempts. Concurrent misses share one in-flight deserialization. Completed values use a bounded weak-reference cache
so a large replay does not retain every materialized object. Cache entries live only for the current Lambda invocation
and are discarded when `ExecutionManager` closes. Cloud test polling creates a fresh cache for each history snapshot
and retains that cache only with the corresponding `TestResult`.

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

The cloud test runner must send initial Lambda input before it receives a durable execution ARN. When configured with a
context-free `ComposableSerDes`, it serializes the invocation payload with the complete configured pipeline so
compression, encryption, and other ordinary transformations remain compatible with the deployed function. When the
persisted SerDes reports that it requires durable context, the runner requires a separate context-free input value
codec via `CloudDurableTestRunner.withInputSerDes(...)`. That codec must not be a composable string-processing pipeline:
an unframed external payload does not identify which input stages ran, while a context-dependent terminal stage must
also accept raw service payloads such as callbacks and invoke results. Fluent configuration preserves that explicit
input codec regardless of whether `withInputSerDes(...)` or `withSerDes(...)` is called first.

### Implementation plan

1. Add `SerDesContext`, `SerDesPayloadKind`, and package-private TLS setter/clearer support. Leave the existing
   `SerDes` methods unchanged.
2. Add the binary-compatible `SerDes.then(...)` default method and `ComposableSerDes` with immutable stage ordering,
   forward serialization, reverse deserialization, external-boundary bypass, terminal-stage validation, null
   short-circuiting, and stage-aware errors.
3. Add `RetryableSerDesException` and `RetrySerDes`, reusing `RetryStrategy` for bounded in-invocation retries.
4. Add `SerDesRunner` with inline execution by default and optional dispatch through
   `DurableConfig.withSerDesExecutorService(...)`. Do not create a default SerDes pool.
5. Update root input/output handling in `DurableExecutor` to run user payload SerDes through `SerDesRunner` while
   leaving `DurableInputOutputSerDes` internal.
6. Update `SerializableDurableOperation`, `InvokeOperation`, `StepOperation`, `WaitForConditionOperation`,
   `CallbackOperation`, `ChildContextOperation`, `MapOperation`, and test helpers to use `SerDesRunner`.
7. Add bounded, invocation-scoped deserialization caching keyed by SerDes identity, entity, payload kind, type, and
   serialized data hash.
8. Update exception serialization and deserialization paths to set `SerDesPayloadKind.EXCEPTION` in TLS.
9. Implement `FileSystemSerDes` in the core `software.amazon.lambda.durable.serde` package with standalone compatibility and
   string-stage modes, `ALWAYS` and `OVERFLOW` storage, `URI` and `HASH` path encodings, envelope parsing, atomic file
   writes where supported by the filesystem, retryable I/O failures, and clear validation errors for missing context or
   invalid stage input.
10. Add unit tests for pipeline ordering, reverse processing, nulls, invalid intermediate stage types, stage failures,
    retry selection, exhaustion, delay handling, interruption, context construction, TLS scoping and restoration,
    inline execution, configured thread-pool isolation, cache hits, cache invalidation, exception reconstruction,
    malformed filesystem envelopes, and core-artifact packaging.
11. Add integration tests with `LocalDurableTestRunner` for multi-stage pipelines, step results, wait-for-condition
    state, invoke payload/result, child context results, map results, repeated `get()`, replay from file pointers, and
    custom exception types.
12. Update README and advanced configuration docs with pipeline and retry examples, filesystem configuration, and
    warnings about `/tmp`, S3 Files flush behavior, and EFS/S3 Files operational requirements.

### Pros

- Delivers the requested parity feature with the smallest new public API surface.
- Uses an extension point customers already understand and can configure per operation.
- Preserves inline SerDes execution by default, avoiding new thread-hop overhead for existing applications.
- Makes serialization, compression, encryption, and storage independently composable without adding a storage-specific
  core interface.
- Makes filesystem storage available without an additional Maven dependency or release artifact.
- Avoids committing the core SDK to a generalized offloading envelope before the storage use cases are proven.
- Closest to the current JavaScript `createFileSystemSerdes` model and issue #463 wording.

### Cons

- Uses serialization as a storage hook, so the name `SerDes` no longer means only object-to-string conversion.
- Requires non-codec stages to obey a string-to-string convention that the current `SerDes` type system cannot enforce.
- May lead to one-off storage SerDes implementations if S3, DynamoDB, or other backends are added later.
- Makes it harder for the SDK to reason separately about serialized text size, offloaded payload references, and storage lifecycle.
- The SDK treats the checkpoint envelope as opaque serialized data, so lifecycle and preview behavior are owned by the SerDes implementation.
- Makes pipeline order and configuration part of checkpoint compatibility for in-flight executions.

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

Keep ordinary SerDes work inline by default for compatibility. Blocking payload I/O can use the explicitly configured
SerDes executor or a distinct offload executor if the team wants independent tuning:

```java
DurableConfig.builder()
        .withSerDesExecutorService(customSerDesExecutor)
        .withPayloadOffloadExecutorService(customOffloadExecutor)
        .build();
```

No executor should be created by default. If a single explicitly configured executor is preferred, name it according
to the broader responsibility, for example `durable-sdk-payload-*`.

Because filesystem/S3/DynamoDB offloading can block, production configurations should provide an executor rather than
run that work inline. Offload work must never use the internal SDK executor.

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
11. Add unit tests for offload envelope compatibility, precedence rules, inline execution, explicitly configured
    thread-pool isolation, cache hits, cache invalidation, exception reconstruction, malformed references, and
    extra-module packaging.
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
| Responsibility boundary | Combines value serialization and storage-reference creation in ordered, composable SerDes stages. | Keeps object encoding in `SerDes` and storage movement in a separate offloader. |
| User configuration | Users configure one SerDes or a `ComposableSerDes` pipeline. Operation-level SerDes selection replaces the whole pipeline. | Users configure both a SerDes and an offloader. The SDK must define global, per-operation, and per-payload precedence. |
| Parity with JS issue | Closest to the current JavaScript `createFileSystemSerdes` model and issue #463 wording. | Diverges from JavaScript naming and shape, though it may be architecturally cleaner for Java. |
| Core SDK changes | Adds the compatible `SerDes.then(...)` default method, `ComposableSerDes`, and `SerDesContext` TLS because the existing serialization methods have no context parameter. | Requires a new core extension point, envelope type, config surface, payload pipeline, and migration story. |
| Applicability | Any compatible SerDes stages can be chained, but storage stages still own their envelope and lifecycle behavior. | Any SerDes output can be offloaded uniformly after serialization. Users can combine Jackson/custom SerDes with any offloader. |
| Envelope ownership | FileSystemSerDes owns the checkpoint envelope (`data`, `file`, preview), so the SDK treats it as opaque serialized data. | SDK owns the checkpoint/offload envelope and must guarantee it composes with replay, errors, callbacks, and test utilities. |
| Caching | SDK can cache deserialized values, but FileSystemSerDes may still do file reads internally unless cache hits happen before SerDes. | SDK can cache at both layers: resolved offloaded payload text and final deserialized object. |
| Exception handling | Works if every exception serialization path is routed through SerDes with `SerDesPayloadKind.EXCEPTION`. | Works uniformly because exception `errorData` is another serialized payload that can be offloaded after SerDes. |
| Third-party storage | S3, DynamoDB, and other backends can be implemented as additional reversible SerDes stages, either in core or separate artifacts based on their dependencies and support model. | Natural home for multiple storage backends: filesystem, S3, DynamoDB, EFS, S3 Files, or custom customer storage. |
| Immediate delivery risk | Lower. Builds on existing customization point. | Higher. Requires new API and more runtime integration. |
| Long-term design risk | Higher. Blurs SerDes semantics and may accumulate storage behavior in serializers. | Lower if offloading grows into a first-class feature, but higher if this remains a one-off filesystem parity feature. |

## Decision

Adopt **Approach A: Reuse SerDes for Offload**, extended with a core `ComposableSerDes` pipeline. It delivers
JavaScript parity, includes filesystem behavior in the existing core artifact, leaves the existing `SerDes` methods unchanged,
and lets customers assemble value encoding, compression, encryption, and storage as independently reusable stages.
Approach B remains a possible future direction if payload offloading grows into a general multi-backend capability
that requires SDK-owned storage envelopes and lifecycle policy.

## Other Alternatives Considered

### Add FileSystemSerDes without SerDesContext

Rejected. A filesystem-backed implementation needs stable operation identity. Without context, it cannot choose a safe file name, distinguish result and exception payloads for the same operation, or avoid collisions across durable executions.

### Publish filesystem-backed offloading as a separate artifact

Rejected for Approach A. The implementation adds no new production dependency, is part of the accepted JavaScript
parity feature, and already relies on core SerDes context and pipeline behavior. A separate artifact would add module,
publishing, documentation, and dependency-management overhead without isolating a distinct dependency graph.

### Add context-aware SerDes overloads

Rejected for Approach A. Explicit overloads are more discoverable, but they force context into every custom
implementation's serialization method surface. Approach A uses `SerDesContext` TLS to keep the existing
`serialize`/`deserialize` signatures unchanged. Approach B does not need SerDes TLS because `PayloadOffloader` receives
`PayloadOffloadContext` explicitly.

### Make SerDes async

Deferred. The TypeScript SDK uses async SerDes because file and service I/O are naturally async in Node.js. Java can
optionally isolate blocking work with an explicitly configured executor while preserving synchronous user-facing
interfaces and inline defaults. A future major version can revisit `CompletionStage<String>` and `CompletionStage<T>`
if there is a stronger need.

### Always run payload storage inline

Rejected as the only execution mode. Inline execution remains the default for backward compatibility and low overhead,
but filesystem-backed storage and delayed retries can block the calling thread. Customers should explicitly configure a
SerDes executor for those stages.

### Run payload storage on the internal SDK executor

Rejected. The internal executor is for checkpointing, polling, and coordination. Blocking storage work should not compete with progress-making SDK tasks.

### Cache inside the filesystem implementation only

Rejected. The repeated-deserialization problem exists for every payload implementation. SDK-level caching also lets the cache key include operation metadata, target type, and the serialized checkpoint string.

### Make DurableInputOutputSerDes user-configurable

Rejected. The backend request/response envelope is protocol data. User payload customization should happen at the user payload boundary, not at the protocol envelope boundary.

## Consequences

Positive:

- Both approaches enable filesystem-backed payload storage without changing the existing `serialize`/`deserialize`
  signatures.
- Approach A makes filesystem-backed storage available from the core SDK without an additional artifact.
- Custom payload implementations get enough context to use external storage safely.
- Customers can compose reusable SerDes stages without creating a bespoke wrapper for each combination.
- Blocking payload work can be isolated from user operation threads with an explicitly configured SerDes executor and
  never runs on the SDK coordination executor.
- Repeated file reads and repeated object reconstruction can be reduced within an invocation.
- User exception type reconstruction remains supported.

Negative:

- Adds optional executor, context, and caching machinery that must stay deterministic.
- Adds storage-specific public API and implementation code to the core SDK artifact.
- Approach A requires thread-local SerDes context because the existing `SerDes` methods do not accept context.
- Approach A relies on a documented string-stage convention that is validated at runtime rather than by Java's type
  system.
- Pipeline ordering and stage configuration must remain compatible with persisted checkpoints.
- The inline default means filesystem I/O and retry delays block the caller when customers do not configure a SerDes
  executor.
- Repeated `get()` calls may return the same object instance in one invocation.
- Filesystem-backed storage introduces operational durability requirements outside the SDK's control.
- Approach A risks overloading the meaning of SerDes.
- Approach B requires a larger core SDK design before delivering filesystem parity.

Deferred:

- A generalized SDK-owned payload-offloading abstraction beyond the SerDes pipeline.
- A fully async Java SerDes or async pipeline contract.
- A separate, explicitly dangerous protocol-envelope customization API.
- File cleanup, retention policies, and lifecycle management for offloaded payloads.

## Shared Design Constraints

These constraints apply to both approaches above.

### Stable payload identity

Both approaches need a stable payload identity that can be used to address external storage. The identity must include the durable execution ARN, operation identity, payload kind, and enough operation metadata to distinguish result, input, callback, wait-for-condition state, and exception payloads.

`entityId` is the primary stable key for external storage. It must be unique within a durable execution and include the payload kind so one operation can safely store multiple values:

| Payload | Example entity ID |
|---------|-------------------|
| Root input | `execution/<execution-operation-id>/input` |
| Root output | `execution/<execution-operation-id>/output` |
| Root exception | `execution/<execution-operation-id>/exception` |
| Step result | `operation/<operation-id>/result/attempt-<attempt>` |
| Step exception | `operation/<operation-id>/exception/attempt-<attempt>` |
| Invoke payload | `operation/<operation-id>/invoke-payload` |
| Invoke result | `operation/<operation-id>/result` |
| Callback result | `operation/<operation-id>/result` |
| Child context result | `operation/<operation-id>/result` |
| Map result | `operation/<operation-id>/result` |
| WaitForCondition state | `operation/<operation-id>/state/attempt-<attempt>` |

Do not include the checkpoint token or raw user payload in the context.

### Packaging boundary

Approach A's filesystem implementation is part of the core SDK because it adds no external production dependency and
is the concrete parity feature accepted by this ADR. A future storage stage may use a separate artifact when it brings
substantial provider-specific dependencies or has an independent support and release model.

| Feature | Artifact ID | Java package |
|---------|-------------|--------------|
| Filesystem payload storage, Approach A | `aws-durable-execution-sdk-java` | `software.amazon.lambda.durable.serde` |
| Filesystem payload storage, Approach B | `aws-durable-execution-sdk-java-extra-filesystem-offloader` | `software.amazon.lambda.durable.extra.filesystem` |
| Event deserialization helpers | `aws-durable-execution-sdk-java-extra-event-deserialization` | `software.amazon.lambda.durable.extra.eventdeserialization` |
| Virtual thread executor helpers | `aws-durable-execution-sdk-java-extra-virtual-thread-pool` | `software.amazon.lambda.durable.extra.virtualthreads` |

The repository should not publish both a filesystem SerDes and a filesystem offloader for the same feature.

### Protocol SerDes boundary

Do not make `DurableInputOutputSerDes` customizable as part of this ADR. It serializes the Lambda Durable Functions backend protocol envelope, not user payloads. Routing that envelope through external payload storage would risk storing checkpoint tokens or protocol data externally and would require the backend to understand file pointers.

User input and output payloads should still use the configured user payload mechanism when they are extracted from or written to the execution operation. The internal `DurableExecutionInput` and `DurableExecutionOutput` envelope remains handled by `DurableInputOutputSerDes`.

If protocol customization is needed later, introduce a separate `ProtocolSerDes` configuration surface with a clear warning that it must produce the exact backend wire format. Do not reuse the user payload `SerDes` for that purpose.
