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
    .build();

var resilientFileSystemStage = new RetrySerDes(
    fileSystemStage,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));

var binaryStage = ComposableBinarySerDesStage.builder()
    .startWith(Utf8StringBinaryCodec.INSTANCE)
    .then(compressionBinaryStage)
    .then(encryptionBinaryStage)
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
string. Each stage must use a self-identifying format: it reverses recognized valid input, rejects recognized malformed
or unsupported input, and returns unrecognized input unchanged. The runner passes the same read-only
`SerDesContext` explicitly to every string stage and binary substage. `ComposableBinarySerDesStage` converts the string
with its starting codec, passes bytes directly through each `BinarySerDesStage`, converts the final bytes to a string
with its ending codec, and adds a reserved versioned frame. Both boundaries are customizable through the same
`StringBinaryCodec` interface; the example performs UTF-8 and Base64 conversion once around the complete
compression/encryption chain.

- `ALWAYS` writes every non-null payload to a file.
- `OVERFLOW` stores small payloads inline and offloads envelopes approaching the 256 KB checkpoint limit.
- `URI` uses readable escaped path segments.
- `HASH` uses fixed-length SHA-256 path segments.

## Structured previews

Java includes the same structured preview controls as the Python and TypeScript SDKs:

```java
var previewConfig = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
    .include(PreviewField.anywhere("id"), PreviewField.path("customer.status"))
    .exclude(PreviewField.anywhere("internal"))
    .mask(PreviewField.anywhere("email"))
    .maskString("***")
    .maxPreviewBytes(4096)
    .build();

var fileSystemStage = FileSystemSerDes.builder(Path.of("/mnt/s3/durable-payloads"))
    .previewConfig(previewConfig)
    .build();

var serDes = new JacksonSerDes().then(fileSystemStage);
```

`INCLUDE_ALL` starts with every leaf visible and applies exclude and mask rules. `EXCLUDE_ALL` starts with no fields
visible; include and mask rules make selected fields visible. `ANYWHERE` matches a field name at any depth, while
`PATH` matches an exact dot-separated path. Exclude rules win over mask rules, and masking implies visibility.

The built-in `previewConfig(...)` parses the incoming stage string as JSON. Use `previewGenerator(...)` for non-JSON
stage values or fully custom logic. Previews are included only in file envelopes. The structured builder defaults to a
4 KB preview budget, and the complete file envelope must still remain below the checkpoint threshold.

## Execution and retries

SerDes runs inline by default. Filesystem access and retry backoff are blocking, so production configurations should
provide a dedicated executor with `withSerDesExecutorService(...)`. It must be different from the user-operation
executor.

Filesystem read and write I/O failures are reported as `RetryableSerDesException`. `RetrySerDes` implements
`SerDesStage`, so the retrying filesystem component can be appended directly to the pipeline. It retries only that
exception type. Malformed envelopes, invalid paths, unsupported stage types, and codec failures are permanent. Retry
delays consume time in the current Lambda invocation, so keep attempts and delays bounded.

## Replay and envelope behavior

Filesystem envelopes include a reserved version marker. `FileSystemSerDes` returns input without that marker unchanged,
allowing raw root input, callback results, and standard Lambda invoke results to continue through the remaining stages
to the pipeline value codec. Payloads containing the reserved marker must be valid supported filesystem envelopes;
malformed marked envelopes and unsupported versions fail instead of falling back to pass-through behavior. An
unrecognized value does not require the explicit `SerDesContext` parameter to be non-null; a recognized filesystem
envelope does.

Offloaded files are content-hashed and immutable. Every serialization uses a unique filename containing the entity
identity, content hash, and UUID, and publishes it with one `CREATE_NEW` write. Existing files are never overwritten,
and publication does not require hard links or renames, making it compatible with both EFS and S3 Files. A failed write
can leave only an unreferenced orphan rather than replacing data referenced by an earlier checkpoint. File envelopes
identify the producing execution and entity. Ordinary checkpoint replay must match that owner, while invoke input and
result boundaries may consume a file owned by the other Lambda execution when both functions use the same shared root
and path encoding. Treat file envelopes as capabilities. Content hashes are verified when reading, and symbolic-link
paths are rejected.

Stages may follow `FileSystemSerDes` to transform its inline or file-reference envelope. The filesystem stage's
`OVERFLOW` and preview-size checks apply before those later transformations, so account for any expansion when staying
within the service checkpoint limit. During deserialization, every stage checks its own marker and returns
unrecognized input unchanged, so raw external payloads can traverse later and earlier stages without being decoded by
them.

The cloud and local test runners always use a separate context-free value codec for the initial Lambda invocation
because an execution ARN is not available yet. By default, they use the configured persisted SerDes when it is a plain
value codec, or the root value codec when it is a `ComposableSerDes`; `withInputSerDes(...)` provides an explicit
override. The runners never use persisted pipeline stages to encode this boundary. Do not configure a
`ComposableSerDes` as the explicit input codec. When the runtime later deserializes the raw input, each persisted stage
passes it through unless its self-identifying format is present. A custom input codec must produce data that the
persisted pipeline's root value codec can decode.

## Storage requirements

Do not use Lambda's ephemeral `/tmp` directory. Durable replay may run in another execution environment where that file
does not exist.

Use a durable shared mount such as EFS or S3 Files. The SDK does not rely on hard links or renames, which S3 Files does
not support. S3 Files can synchronize writes asynchronously, so a runtime crash before a flush can lose recent data;
use it only when that durability tradeoff is acceptable.

The SDK does not delete payload files. Configure an appropriate retention or lifecycle policy for the backing storage.
