# Filesystem SerDes

`aws-durable-execution-sdk-java-extra-filesystem-serdes` stores durable user payloads on a shared filesystem while
keeping small, versioned file-reference envelopes in checkpoints.

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-extra-filesystem-serdes</artifactId>
    <version>VERSION</version>
</dependency>
```

## Pipeline configuration

The preferred configuration uses `FileSystemSerDes` as a reversible string stage after a value codec:

```java
var fileSystemStage = FileSystemSerDes.stageBuilder(Path.of("/mnt/efs/durable-payloads"))
    .storageMode(FileSystemStorageMode.ALWAYS)
    .pathEncoding(FileSystemPathEncoding.URI)
    .previewGenerator(json -> Map.of("format", "json"))
    .build();

var resilientFileSystemStage = new RetrySerDes(
    fileSystemStage,
    RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)));

var serDes = new JacksonSerDes().then(resilientFileSystemStage);
var serDesExecutor = Executors.newFixedThreadPool(4);

return DurableConfig.builder()
    .withSerDes(serDes)
    .withSerDesExecutorService(serDesExecutor)
    .build();
```

Serialization runs from `JacksonSerDes` to the filesystem stage. Deserialization runs in reverse. Additional reversible
string stages such as compression or encryption can be inserted with `then(...)`.

- `ALWAYS` writes every non-null payload to a file.
- `OVERFLOW` stores small payloads inline and offloads envelopes approaching the 256 KB checkpoint limit.
- `URI` uses readable escaped path segments.
- `HASH` uses fixed-length SHA-256 path segments.

The preview generator receives the incoming stage string. Its output is included only in file envelopes and the final
envelope must remain below the checkpoint threshold.

For compatibility, `FileSystemSerDes.builder(path)` creates a standalone SerDes with `JacksonSerDes` as its default
value codec. A custom standalone codec can be supplied with `.delegate(...)`.

## Execution and retries

SerDes runs inline by default. Filesystem access and retry backoff are blocking, so production configurations should
provide a dedicated executor with `withSerDesExecutorService(...)`. It must be different from the user-operation
executor.

Filesystem read and write I/O failures are reported as `RetryableSerDesException`. `RetrySerDes` retries only that
exception type. Malformed envelopes, invalid paths, unsupported stage types, and codec failures are permanent. Retry
delays consume time in the current Lambda invocation, so keep attempts and delays bounded.

## Replay and envelope behavior

Filesystem envelopes include a reserved version marker. Raw root input, callback results, and standard Lambda invoke
results pass through when they have not yet been wrapped by this SerDes.

Offloaded files are content-addressed and immutable. Updating wait-for-condition state or retry results creates a new
path instead of replacing a file referenced by an earlier checkpoint. Repeating the same write can safely reuse the
same file. File envelopes identify the producing execution and entity. Ordinary checkpoint replay must match that
owner, while invoke input and result boundaries may consume a file owned by the other Lambda execution when both
functions use the same shared root and path encoding. Treat file envelopes as capabilities. Content hashes are
verified when reading, and symbolic-link paths are rejected.

`CloudDurableTestRunner` uses the first value-codec stage for the initial Lambda invocation, before an execution ARN is
available, and uses the complete pipeline for persisted history. When using standalone `FileSystemSerDes`, configure a
separate initial-input codec with `withInputSerDes(...)`. An explicitly supplied input SerDes is used exactly as
configured, including every stage in a composable pipeline.

## Storage requirements

Do not use Lambda's ephemeral `/tmp` directory. Durable replay may run in another execution environment where that file
does not exist.

Use a durable shared mount such as EFS. S3 Files can synchronize writes asynchronously, so a runtime crash before a
flush can lose recent data; use it only when that durability tradeoff is acceptable.

The SDK does not delete payload files. Configure an appropriate retention or lifecycle policy for the backing storage.
