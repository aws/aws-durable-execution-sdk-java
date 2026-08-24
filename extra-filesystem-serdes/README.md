# Filesystem SerDes

`aws-durable-execution-sdk-java-extra-filesystem-serdes` stores durable user payloads on a shared filesystem while
keeping small file-pointer envelopes in checkpoints.

## Installation

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-extra-filesystem-serdes</artifactId>
    <version>VERSION</version>
</dependency>
```

## Configuration

```java
var serDes = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
    .storageMode(FileSystemStorageMode.ALWAYS)
    .pathEncoding(FileSystemPathEncoding.URI)
    .delegate(new JacksonSerDes())
    .build();

return DurableConfig.builder()
    .withSerDes(serDes)
    .build();
```

- `ALWAYS` writes every non-null payload to a file.
- `OVERFLOW` stores small payloads inline and offloads envelopes approaching the 256 KB checkpoint limit.
- `URI` uses readable escaped path segments.
- `HASH` uses fixed-length SHA-256 path segments.

An optional preview can remain visible in the checkpoint envelope:

```java
.previewGenerator(value -> Map.of("summary", "order payload"))
```

## Storage requirements

Do not use Lambda's ephemeral `/tmp` directory. Durable replay may run in another execution environment where that file
does not exist.

Use a durable shared mount such as EFS. S3 Files can synchronize writes asynchronously, so a runtime crash before a
flush can lose recent data; use it only when that durability tradeoff is acceptable.

The SDK does not delete payload files. Configure an appropriate retention or lifecycle policy for the backing storage.
