# Serialization and SerDes pipelines

The SDK uses a `SerDes` to convert durable values between Java objects and the strings stored in checkpoints. The
default is `JacksonSerDes`, so most applications do not need to configure serialization.

The core SDK also supports composable pipelines for transforming the serialized string before it is persisted. A
pipeline has one context-free value codec followed by zero or more reversible `SerDesStage` instances:

```text
serialization:   Object -> SerDes -> String -> stage 1 -> stage 2 -> String
deserialization: Object <- SerDes <- String <- stage 1 <- stage 2 <- String
```

Serialization runs stages in declaration order. Deserialization runs them in reverse order.

## Value codecs

`SerDes` is the object-to-string boundary:

```java
public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, TypeToken<T> typeToken);
}
```

The `TypeToken` preserves runtime type information needed to deserialize generic Java types. `JacksonSerDes` implements
this interface and can be constructed with a custom Jackson `ObjectMapper`:

```java
var objectMapper = JsonMapper.builder()
    .findAndAddModules()
    .build();

var serDes = new JacksonSerDes(objectMapper);

return DurableConfig.builder()
    .withSerDes(serDes)
    .build();
```

Existing custom `SerDes` implementations remain valid. Pipeline behavior is opt-in: calling `then(...)` creates a
composable `SerDes`, while using a value codec by itself retains the existing object-to-string behavior.

## Selecting a SerDes

`DurableConfig.Builder.withSerDes(...)` sets the default SerDes for persisted values:

```java
return DurableConfig.builder()
    .withSerDes(new JacksonSerDes())
    .build();
```

Operation configuration builders also support `serDes(...)` for overriding the complete SerDes used by that operation.
This is a replacement, not an additional stage appended to the global pipeline. `InvokeConfig` additionally supports
`payloadSerDes(...)` for the invoked function's payload; `serDes(...)` controls the invoke result.

## Composable pipelines

Every top-level pipeline stage implements `SerDesStage` and transforms a string:

```java
public interface SerDesStage {
    String serialize(String value, SerDesContext context);

    String deserialize(String data, SerDesContext context);
}
```

Append stages to a value codec with `SerDes.then(...)`:

```java
SerDes serDes = new JacksonSerDes()
    .then(compressionStage)
    .then(encryptionStage)
    .then(storageStage);
```

The returned value is a `SerDes`, so applications can configure or pass a pipeline anywhere a regular `SerDes` is
accepted. Additional `then(...)` calls continue the same immutable pipeline.

Stages must use a self-identifying, normally versioned representation. During deserialization, a stage must:

- reverse input that is valid and uses its format;
- throw an exception for input that identifies itself as the stage's format but is malformed or uses an unsupported
  version; and
- return input unchanged when it does not use the stage's format.

The pass-through rule allows raw invocation payloads, callback results, and standard Lambda invoke results to traverse
the pipeline and reach its value codec. It also allows stages to be added without making older checkpoint values
unreadable. Each stage owns the compatibility policy for its format; the pipeline does not add a shared outer
envelope.

The pipeline short-circuits a `null` value at its boundary. A stage must not return `null` for non-null input.

## Binary transformations

Compression, encryption, and similar transformations are usually easier to implement on bytes. A
`BinarySerDesStage` transforms `byte[]` values:

```java
public interface BinarySerDesStage {
    byte[] serialize(byte[] value, SerDesContext context);

    byte[] deserialize(byte[] data, SerDesContext context);
}
```

Use `ComposableBinarySerDesStage` to expose several binary stages as one string-to-string `SerDesStage`:

```java
var binaryStage = ComposableBinarySerDesStage.builder()
    .startWith(Utf8StringBinaryCodec.INSTANCE)
    .then(compressionBinaryStage)
    .then(encryptionBinaryStage)
    .endWith(Base64StringBinaryCodec.INSTANCE)
    .build();

SerDes serDes = new JacksonSerDes()
    .then(binaryStage);
```

The builder follows serialization processing order:

1. `startWith(...)` converts the incoming string to bytes.
2. Each `then(...)` stage transforms those bytes.
3. `endWith(...)` converts the final bytes back to a string.

Deserialization reverses that order. Both boundaries implement the same `StringBinaryCodec` interface and are
customizable. The core SDK includes UTF-8 and Base64 codecs.

`ComposableBinarySerDesStage` adds a reserved, versioned frame to its output. It decodes recognized frames, rejects
malformed or unsupported frames, and passes unrecognized strings through unchanged.

## Stage context

The SDK passes a read-only `SerDesContext` explicitly to every `SerDesStage` and nested `BinarySerDesStage`. The
context describes the durable execution, entity, operation, attempt, and payload kind being processed.

During serialization, `originalValue()` contains the object supplied to the root value codec. This lets a stage derive
metadata such as a preview from the original object even though its direct input is a string. During deserialization,
`originalValue()` is `null`.

Root `SerDes` value codecs do not receive `SerDesContext`; they remain usable outside a durable execution. A context can
also be `null` when an application invokes a stage directly outside the SDK, so stages should document whether their
recognized format requires durable context.

The SDK caches successful deserialization results for the current Lambda invocation in a bounded, weak-reference
cache. Concurrent requests for the same persisted value share one in-flight deserialization. Results are not cached
across invocations.

## Retries and execution

`RetrySerDesStage` wraps a `SerDesStage`, and `RetryBinarySerDesStage` wraps a `BinarySerDesStage`:

```java
var resilientStorageStage = new RetrySerDesStage(
    storageStage,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));
```

These wrappers retry only `RetryableSerDesException`. Permanent errors, such as malformed envelopes or codec failures,
are not retried. `RetryBinarySerDesStage` snapshots its input and gives every attempt a fresh byte array, so a failed
delegate cannot leak in-place mutations into the next attempt. Retry delays consume time in the current Lambda
invocation, so keep strategies short and bounded.

SerDes runs inline on the calling thread by default, preserving the existing no-thread-pool behavior and avoiding a
thread hop for in-memory serialization. Blocking stages, including filesystem access and retry backoff, can use a
dedicated executor:

```java
return DurableConfig.builder()
    .withSerDes(serDes)
    .withSerDesExecutorService(Executors.newFixedThreadPool(4))
    .build();
```

The SerDes executor must be different from the user-operation executor to avoid deadlock when the operation pool is
saturated.

## Initial invocation payloads

The initial Lambda invocation uses a separate, context-free value codec because no durable execution context exists
yet. `DurableExecutor` deserializes this payload directly with `DurableConfig.getInputSerDes()` and does not run the
persisted pipeline's stages at this boundary. This also prevents an external payload from being mistaken for a
persisted stage frame.

By default, `DurableConfig` uses the configured SerDes if it is a plain value codec, or the root value codec if it is a
`ComposableSerDes`. Use `DurableConfig.Builder.withInputSerDes(...)` to select another input codec. The explicit input
codec must not be a `ComposableSerDes`, but it does not need to be wire-compatible with the persisted pipeline:

```java
var config = DurableConfig.builder()
    .withSerDes(persistedPipeline)
    .withInputSerDes(externalInputCodec)
    .build();
```

`LocalDurableTestRunner.withInputSerDes(...)` updates both sides of the local boundary: the runner serializes with the
selected codec and the local runtime deserializes with it. `CloudDurableTestRunner.withInputSerDes(...)` controls the
invocation sent to AWS; the deployed handler must configure the same codec through `DurableConfig`.

Chained invokes preserve the existing standard-Lambda wire contract by default: the caller uses its context-free input
codec unless `InvokeConfig.payloadSerDes(...)` is set, and sends that serialized value unchanged. This remains
compatible with standard Lambda functions, non-Java durable functions, and older Java SDK versions.

To offload or otherwise process an invoke payload through a persisted pipeline, both Java durable handlers must
configure compatible pipelines. The target must explicitly accept framed persisted payloads:

```java
var targetConfig = DurableConfig.builder()
    .withSerDes(persistedPipeline)
    .withPersistedSerDesForChainedInvokePayloads(true)
    .build();
```

The caller must opt in for that invoke:

```java
var result = context.invoke(
    "invoke-compatible-handler",
    targetFunction,
    payload,
    Result.class,
    InvokeConfig.builder()
        .usePersistedSerDesForPayload(true)
        .build());
```

The caller opt-in adds a reserved, versioned SDK source frame outside the serialized payload. A target removes the
frame and deserializes the enclosed value with its persisted SerDes only when its own configuration enables this
capability. Otherwise all execution input, including values that collide with or spoof the frame prefix, continues
through the context-free input codec. The frame is not trusted backend metadata, so enable target acceptance only when
every caller allowed to reach the handler may use the persisted pipeline. The exact source frame and filesystem
envelope are specified in [Persisted SerDes wire formats](../wire-formats/persisted-serdes.md).

## Filesystem-backed payload storage

`FileSystemSerDesStage` stores serialized payloads on a durable shared filesystem and leaves small, versioned
file-reference envelopes in checkpoints. It is included in the core `aws-durable-execution-sdk-java` artifact under
the `software.amazon.lambda.durable.serde.filesystem` Java package. See
[Persisted SerDes wire formats](../wire-formats/persisted-serdes.md) for the normative envelope schema, digest, path,
ownership, and cross-execution validation rules.

Configure it after a value codec:

```java
var fileSystemStage = FileSystemSerDesStage.builder(Path.of("/mnt/efs/durable-payloads"))
    .storageMode(FileSystemStorageMode.OVERFLOW)
    .pathEncoding(FileSystemPathEncoding.HASH)
    .checkpointEnvelopeLimitBytes(configuredCheckpointEnvelopeLimitBytes)
    .build();

var resilientFileSystemStage = new RetrySerDesStage(
    fileSystemStage,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));

SerDes serDes = new JacksonSerDes()
    .then(resilientFileSystemStage);

return DurableConfig.builder()
    .withSerDes(serDes)
    .withSerDesExecutorService(Executors.newFixedThreadPool(4))
    .build();
```

The storage modes are:

- `ALWAYS`: store every non-null payload in a file.
- `OVERFLOW`: keep payloads inline until the checkpoint envelope approaches the configured size limit.

The path encodings are:

- `URI`: use readable escaped path segments.
- `HASH`: use fixed-length SHA-256 path segments.

`checkpointEnvelopeLimitBytes(...)` controls the maximum UTF-8 size accepted for both inline and file envelopes. It
defaults to 255 KiB and can be increased when the durable execution service supports a larger payload limit.

### Structured previews

File envelopes can include a structured preview:

```java
var previewConfig = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
    .include(PreviewField.anywhere("id"), PreviewField.path("customer.status"))
    .exclude(PreviewField.anywhere("internal"))
    .mask(PreviewField.anywhere("email"))
    .maskString("***")
    .maxPreviewBytes(4096)
    .build();

var fileSystemStage = FileSystemSerDesStage.builder(Path.of("/mnt/efs/durable-payloads"))
    .previewConfig(previewConfig)
    .build();
```

`INCLUDE_ALL` starts with every leaf visible and then applies exclude and mask rules. `EXCLUDE_ALL` starts with no
fields visible; include and mask rules make selected fields visible. `PreviewField.anywhere(...)` matches a field name
at any depth, while `PreviewField.path(...)` matches an exact dot-separated path. Exclude rules win over mask rules,
and masking implies visibility.

The built-in `previewConfig(...)` parses the incoming stage string as JSON. Use `previewGenerator(...)` for non-JSON
stage values or custom logic. The generator receives the stage string and `SerDesContext`, including
`originalValue()` during serialization. Custom generators must avoid exposing sensitive fields.

Previews are included only in file envelopes. The structured builder defaults to a 4 KiB preview budget, and the
complete envelope must remain below the configured checkpoint limit.

### Replay and envelope behavior

Filesystem envelopes contain a reserved version marker. The stage passes strings without this marker through
unchanged. A string containing the marker must be a valid, supported filesystem envelope; malformed marked envelopes
and unsupported versions fail rather than falling back to pass-through behavior.

Payload files are content-hashed and immutable. Each serialization publishes a unique filename with a single
`CREATE_NEW` write. Existing files are never overwritten, and publication does not require hard links or renames.
Every inline and file envelope records the payload's SHA-256 digest. During deserialization, the stage verifies the
restored bytes against that envelope digest; file payloads must also have a content-addressed filename consistent with
the digest. The stage traverses directories with `SecureDirectoryStream`, disables symbolic-link following, and holds
the relative directory handles through each file read or write. This makes path validation and access one safe
operation even if another process changes names on the shared filesystem. Providers without
`SecureDirectoryStream` support are rejected. The stage also validates that ordinary checkpoint replay matches the
execution and entity that produced the reference. Invoke input and result boundaries can consume a file owned by the
other Lambda execution when both functions use the same shared root and path encoding.

Stages may follow `FileSystemSerDesStage` to transform its inline or file-reference envelope. `OVERFLOW` and preview
size checks occur before those later stages, so account for any expansion when staying within the service checkpoint
limit.

### Storage requirements

Do not use Lambda's ephemeral `/tmp` directory. Durable replay may run in another execution environment where the file
does not exist.

Use a durable shared mount such as EFS or S3 Files. The stage does not rely on hard links or renames, which S3 Files
does not support. The mounted Java filesystem provider must expose `SecureDirectoryStream`; ordinary Linux EFS and S3
Files mounts use the default provider that supplies it. S3 Files can synchronize writes asynchronously, so a runtime
crash before a flush can lose recent data; use it only when that durability tradeoff is acceptable.

The SDK does not delete payload files. Configure an appropriate retention or lifecycle policy for the backing
storage.
