# Filesystem SerDes

`FileSystemSerDes` stores durable operation payloads on a shared filesystem and
checkpoints a small JSON file reference. It follows the .NET SDK's per-operation
serializer model and the JavaScript/Python SDKs' always/overflow storage modes.

## Dependency

Use the same version as your core SDK:

```xml
<dependency>
    <groupId>software.amazon.lambda.durable</groupId>
    <artifactId>aws-durable-execution-sdk-java-extra-filesystem-serdes</artifactId>
    <version>VERSION</version>
</dependency>
```

## Usage

```java
import java.nio.file.Path;
import java.util.Map;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.extra.filesystem.FileSystemPathEncoding;
import software.amazon.lambda.durable.extra.filesystem.FileSystemSerDes;
import software.amazon.lambda.durable.extra.filesystem.FileSystemStorageMode;

var files = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
        .storageMode(FileSystemStorageMode.OVERFLOW)
        .pathEncoding(FileSystemPathEncoding.HASH)
        .previewGenerator(value -> Map.of("characters", ((String) value).length()))
        .build();

var payload = context.step("load-document", String.class,
        stepContext -> documentStore.load(documentId),
        StepConfig.builder().serDes(files).build());
```

The serializer defaults to `ALWAYS`, `URI`, and `JacksonSerDes`. Set
`.delegate(customSerDes)` to preserve a custom serializer's encoding. The delegate
receives the explicit serialization context, and its returned string is stored
unchanged as UTF-8. A null serialized string remains null without creating a file.
Custom delegates and preview generators must support concurrent use.

| Option | Behavior |
| --- | --- |
| `ALWAYS` | Write every non-null serialized payload to a file. |
| `OVERFLOW` | Store an inline envelope up to 255 KiB; offload larger envelopes. The size includes JSON escaping and UTF-8 encoding. |
| `URI` | Percent-encode each slash-separated ARN segment and the entire entity ID. Human-readable paths retain the full ARN identity. Long names may exceed filesystem limits. |
| `HASH` | SHA-256 the full ARN and entity ID into fixed-length path segments. |
| `previewGenerator` | Add a small JSON object alongside a file pointer. Return null to omit it. Previews are visible in checkpoint APIs; include only intended fields. |

A pointer plus preview must also fit within 255 KiB. Oversized previews fail
serialization and the unpublished file is removed. Preview failures propagate.

## Operations and external boundaries

Configure `serDes(files)` on `StepConfig`, `RunInChildContextConfig`, `MapConfig`,
`ParallelBranchConfig`, or `WaitForConditionConfig`. Step exceptions are offloaded
through the same serializer with a separate entity identity. Polling states keep
separate immutable files even though they share an operation ID.

Like the .NET filesystem serializer, this implementation requires operation
context. Do not use it as `DurableConfig.withSerDes(files)`: the global serializer
also handles ordinary Lambda input and final output without operation identity.
Keep handler inputs/outputs small and normally encoded. Calling the two original
context-free SerDes methods throws a descriptive `SerDesException`.

`CallbackConfig.serDes(files)` and `InvokeConfig.serDes(files)` expect the producer
to supply a filesystem envelope and both sides to access the same mounted path.
The producer can call the explicit context overload with its own stable identity:

```java
import software.amazon.lambda.durable.serde.SerDesContext;

var envelope = files.serialize(value,
        new SerDesContext(durableExecutionArn, "external/approval/result"));
// Send envelope as the callback result, or return it from the invoked function.
```

The consumer context identifies the receiving operation; it need not equal the
producer context. Ordinary JSON callback/invoke results are not automatically
interpreted as filesystem envelopes. For a normal JSON invoke request with an
offloaded response, explicitly set `InvokeConfig.payloadSerDes(new JacksonSerDes())`
and `.serDes(files)`. Offloading the request itself with `.payloadSerDes(files)`
requires the invoked function to explicitly decode that envelope.

Test runners retain their configured global serializer when inspecting step
results. For a step with a custom filesystem serializer, inspect
`getStepDetails().result()` and decode it with the same serializer and a
`SerDesContext(executionArn, "operation/" + operationId + "/result")`.

## Storage and replay

Use a durable, shared mount such as EFS with the same absolute path on every
execution environment. Lambda `/tmp` is ephemeral and unsuitable for production
replay. Filesystems with delayed remote synchronization, including S3 Files, can
lose recent writes on runtime failure; local flush completion does not remove
that storage-specific durability limitation.

Each successful serialization creates a new file, closes and flushes it, then
returns its pointer. Later writes for the same execution/entity never change
previously returned references. A failed write or envelope construction removes
its unpublished file when possible. A process crash can leave an orphaned file.

Files are never automatically deleted, including after completion. Retain them
for the complete execution and replay lifetime and arrange external cleanup for
completed/abandoned executions and orphaned writes. Repeated polling, retries,
and child-context execution can create additional files.

The mount and base directory must be controlled by the application. Readers reject
relative paths, traversal outside the base, and symlinks below it; writers reject
symlink execution directories. These checks do not isolate the application from
other processes that can modify its mount. Filesystem permissions and retention
remain the application's responsibility. Missing files and I/O failures surface
as `SerDesException` with their original cause.

## Checkpoint format

```json
{"data":"<delegate-serialized string>"}
{"file":"<absolute path to an immutable payload file>"}
{"file":"<absolute path>","preview":{"characters":400000}}
```

Exactly one string field, `data` or `file`, is required. Inline data is a JSON
string containing the delegate payload, not an embedded JSON object. A file
contains the delegate payload itself. Null values use the existing null SerDes
representation. Switching serializers for a running execution requires preserving
its original checkpoint format and storage access.

This matches the JavaScript/Python envelope shape with JSON delegates; it is not
a claim of cross-language replay compatibility. In particular, .NET's inline
format uses base64 bytes.

## Validation

```bash
mvn -pl extra-filesystem-serdes -am \
    -Dtest=FileSystemSerDesTest,FileSystemSerDesIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

These tests use temporary local directories to validate serialization and replay.
They do not validate deployed EFS permissions or a mount's remote persistence.
