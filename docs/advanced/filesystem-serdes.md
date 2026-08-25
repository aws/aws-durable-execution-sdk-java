# Filesystem SerDes

`FileSystemSerDes` stores durable user payloads on a shared filesystem while keeping small, versioned file-reference
envelopes in checkpoints. It is included in the core `aws-durable-execution-sdk-java` artifact.

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java</artifactId>
    <version>VERSION</version>
</dependency>
```

## Pipeline configuration

`FileSystemSerDes` is a reversible `SerDesStage` that must be configured after a value codec:

```java
var fileSystemStage = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
    .storageMode(FileSystemStorageMode.ALWAYS)
    .pathEncoding(FileSystemPathEncoding.URI)
    .previewGenerator(json -> Map.of("format", "json"))
    .build();

var resilientFileSystemStage = new RetrySerDes(
    fileSystemStage,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));

var binaryStage = ComposableBinarySerDesStage.builder()
    .startWith(Utf8StringBinaryCodec.INSTANCE)
    .then(compressionBinarySerDes)
    .then(encryptionBinarySerDes)
    .endWith(Base64StringBinaryCodec.INSTANCE)
    .build();

var serDes = new JacksonSerDes()
    .then(binaryStage)
    .then(resilientFileSystemStage)
    .then(checkpointEnvelopeStage);
var serDesExecutor = Executors.newFixedThreadPool(4);

return DurableConfig.builder()
    .withSerDes(serDes)
    .withSerDesExecutorService(serDesExecutor)
    .build();
```

Serialization follows the declaration order above and deserialization runs in reverse. The first component is the
`SerDes` value codec; every component appended with `then(...)` implements `SerDesStage` and consumes and produces a
string. `ComposableBinarySerDesStage` converts the string with its starting codec, passes bytes directly through each
`BinarySerDes`, then converts the final bytes to a string with its ending codec. Both boundaries are customizable
through the same `StringBinaryCodec` interface; the example performs UTF-8 and Base64 conversion once around the
complete compression/encryption chain.

- `ALWAYS` writes every non-null payload to a file.
- `OVERFLOW` stores small payloads inline and offloads envelopes approaching the 256 KB checkpoint limit.
- `URI` uses readable escaped path segments.
- `HASH` uses fixed-length SHA-256 path segments.

The preview generator receives the incoming stage string. Its output is included only in file envelopes and the final
envelope must remain below the checkpoint threshold.

## Execution and retries

SerDes runs inline by default. Filesystem access and retry backoff are blocking, so production configurations should
provide a dedicated executor with `withSerDesExecutorService(...)`. It must be different from the user-operation
executor.

Filesystem read and write I/O failures are reported as `RetryableSerDesException`. `RetrySerDes` implements
`SerDesStage`, so the retrying filesystem component can be appended directly to the pipeline. It retries only that
exception type. Malformed envelopes, invalid paths, unsupported stage types, and codec failures are permanent. Retry
delays consume time in the current Lambda invocation, so keep attempts and delays bounded.

## Replay and envelope behavior

Filesystem envelopes include a reserved version marker. Raw root input, callback results, and standard Lambda invoke
results bypass every intermediate stage and decode directly with the pipeline value codec when they have not yet
been wrapped by this SerDes. Payloads containing the reserved marker must be valid supported filesystem envelopes;
malformed marked envelopes and unsupported versions fail instead of falling back to raw-data decoding.

Offloaded files are content-addressed and immutable. Updating wait-for-condition state or retry results creates a new
path instead of replacing a file referenced by an earlier checkpoint. Publication uses an atomic hard-link
create-if-absent operation when the provider supports it, allowing repeated identical writes to reuse one file. On
providers that do not support hard links, it retains the completed, uniquely named content-addressed staging file. The
fallback path is not exposed in an envelope until its write completes, so a crash can leave only an unreferenced orphan
rather than a partial checkpoint target. File envelopes identify the producing execution and entity. Ordinary
checkpoint replay must match that owner, while invoke input and result boundaries may consume a file owned by the
other Lambda execution when both functions use the same shared root and path encoding. Treat file envelopes as
capabilities. Content hashes are verified when reading, and symbolic-link paths are rejected.

Stages may follow `FileSystemSerDes` to transform its inline or file-reference envelope. The filesystem stage's
`OVERFLOW` and preview-size checks apply before those later transformations, so account for any expansion when staying
within the service checkpoint limit. During deserialization of a raw external payload, later stages run before
`FileSystemSerDes` can identify the external boundary; those stages must tolerate or explicitly bypass payloads that
have not passed through the pipeline.

The cloud and local test runners always use a context-free SerDes for the initial Lambda invocation because an
execution ARN is not available yet. They use an entire plain or context-free composable persisted SerDes by default.
For a pipeline containing `FileSystemSerDes`, they use the root value codec and bypass every stage because the
filesystem stage requires durable context. `withInputSerDes(...)` accepts plain and context-free composable overrides
but rejects pipelines containing a context-dependent stage. A custom input SerDes must produce data that the persisted
pipeline can decode at the external-input boundary.

## Storage requirements

Do not use Lambda's ephemeral `/tmp` directory. Durable replay may run in another execution environment where that file
does not exist.

Use a durable shared mount such as EFS. S3 Files can synchronize writes asynchronously, so a runtime crash before a
flush can lose recent data; use it only when that durability tradeoff is acceptable.

The SDK does not delete payload files. Configure an appropriate retention or lifecycle policy for the backing storage.
