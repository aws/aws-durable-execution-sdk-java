# Serialization and Filesystem Storage

The SDK uses `SerDes` for handler input/output, durable operation results and state, invoke payloads/results, callback
results, and serialized exceptions.

## Custom value encoding

Implement `SerDes` to control object-to-string encoding:

```java
public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, TypeToken<T> typeToken);
}
```

Configure a default:

```java
return DurableConfig.builder()
        .withSerDes(new MyCustomSerDes())
        .build();
```

Operation configuration can override the default:

```java
var invokeConfig = InvokeConfig.builder()
        .payloadSerDes(new JacksonSerDes())
        .serDes(fileSystemSerDes)
        .build();
```

This sends an ordinary JSON invoke payload while decoding the invoke result through filesystem storage. The same
selection model applies to steps, callbacks, child contexts, map, parallel, and wait-for-condition.

## SerDesContext

SDK-managed calls pass durable identity explicitly through backward-compatible default methods:

```java
public interface SerDes {
    String serialize(Object value);

    default String serialize(Object value, SerDesContext context) {
        return serialize(value);
    }

    <T> T deserialize(String data, TypeToken<T> typeToken);

    default <T> T deserialize(String data, TypeToken<T> typeToken, SerDesContext context) {
        return deserialize(data, typeToken);
    }
}
```

Existing implementations continue to implement only the original methods. Context-aware implementations override the
new overloads. The SDK supplies a non-null context for managed calls; direct calls to the original methods are
context-free.

Entity IDs distinguish every persisted payload owned by the same execution or operation:

| Payload | Entity ID |
| --- | --- |
| Root input | `<execution-operation-id>/input` |
| Root output | `<execution-operation-id>/output` |
| Root exception | `<execution-operation-id>/exception` |
| Invoke request | `<operation-id>/invoke-payload` |
| Operation result or state | `<operation-id>/result` |
| Operation exception | `<operation-id>/exception` |

Custom external-storage SerDes implementations can use `entityId` as part of a key namespace. Every serialization must
still publish immutable content and return a unique or versioned reference. Never overwrite content reachable through
an older reference: an invocation can stop after publishing new content but before its checkpoint update commits, and
replay must continue to resolve the older checkpoint.

## Execution and caching

SerDes calls execute inline by default. Configure a dedicated executor when serialization performs blocking filesystem
or network I/O:

```java
return DurableConfig.builder()
        .withSerDes(fileSystemSerDes)
        .withSerDesExecutorService(Executors.newFixedThreadPool(8))
        .build();
```

Do not reuse the user-operation executor. Operations synchronously wait for SerDes calls, so sharing a saturated pool
can deadlock.

Each Lambda invocation owns a `SerDesRunner`. It shares concurrent reads and keeps up to 256 successful
deserializations in a weak-reference LRU cache. Cache identity includes the SerDes instance, execution ARN, entity ID,
target type, serialized payload hash, and the entity's serialization generation. Every serialization advances that
generation after the SerDes call finishes, so a deterministic external reference that is reused for new state cannot
return an older cached object during the same invocation. This cache rule does not make mutable references replay-safe;
external references must remain immutable across invocations and checkpoint failures.

Local and cloud testing utilities propagate the same contexts and caching behavior through `TestResult`,
`TestOperation`, history processing, and asynchronous execution snapshots.

## FileSystemSerDes

`FileSystemSerDes` stores serialized values on a shared durable filesystem:

```java
var fileSystemSerDes = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
        .delegate(new JacksonSerDes())
        .storageMode(FileSystemSerDesMode.OVERFLOW)
        .pathEncoding(FileSystemPathEncoding.HASH)
        .checkpointEnvelopeLimitBytes(256 * 1024 - 1024)
        .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.anywhere("id"), PreviewField.path("status"))
                .mask(PreviewField.anywhere("email"))
                .build())
        .build();
```

### Storage modes

| Mode | Behavior |
| --- | --- |
| `ALWAYS` | Writes every SDK-managed non-null payload to a file. |
| `OVERFLOW` | Keeps the complete envelope inline until it exceeds the configured limit. |

The default checkpoint-envelope limit is 255 KiB and can be changed with
`checkpointEnvelopeLimitBytes(...)`.

### Path encoding

| Encoding | Behavior |
| --- | --- |
| `URI` | Percent-encodes readable execution and entity path segments. |
| `HASH` | Uses fixed-length SHA-256 segments for arbitrary or long identifiers. |

Files include the serialized-payload digest and a unique suffix. They are published with `CREATE_NEW`; failed writes are
cleaned up. Each envelope includes a SHA-256 digest that is verified when the file is loaded.

The mounted filesystem provider must support `SecureDirectoryStream`. The SDK traverses every directory relative to an
already-open parent with symlink following disabled and performs file I/O with `NOFOLLOW_LINKS`. Providers without this
capability fail closed. The configured base directory must already exist; the SDK never creates path components.

Only objects containing the reserved version marker are treated as filesystem envelopes. Ordinary JSON with `data` or
`file` fields is passed to the configured delegate.

```json
{"__durable_execution_filesystem_serdes":1,"data":"<delegate payload>","sha256":"<digest>"}
{"__durable_execution_filesystem_serdes":1,"file":"/mnt/efs/...json","sha256":"<digest>"}
```

See [Filesystem SerDes wire format](../wire-formats/filesystem-serdes.md) for exact schemas, member validation, path
construction, security requirements, and versioning.

### Structured previews

`PreviewConfig` supports:

- `INCLUDE_ALL` or `EXCLUDE_ALL` defaults;
- field matching anywhere or by exact dotted path;
- include, exclude, and mask selectors;
- configurable mask text;
- a default 4 KiB preview budget.

Object arrays are flattened at their containing path, while scalar arrays are retained. Field names containing dots are
skipped because they are ambiguous with path selectors. Use `previewGenerator(...)` for custom behavior.

### Initial input

The durable execution ARN does not exist while the caller serializes the initial Lambda input. A direct call to the
original `FileSystemSerDes.serialize()` method therefore delegates normally. After invocation begins, root input
deserialization and all persisted output/operation payloads use the context-aware methods.

### Operational requirements

- Do not use Lambda `/tmp`; replay may run in another execution environment.
- Use a shared durable mount such as EFS.
- Pre-provision the configured base directory.
- If using S3 Files, account for its synchronization and crash-durability behavior.
- Verify that the Java filesystem provider for the mount supports `SecureDirectoryStream`.
- Configure retention and cleanup separately; the SDK does not delete completed payload files.
- Chained-invoke boundaries that use filesystem storage require compatible mount paths on both sides.

## Retrying transient SerDes failures

Filesystem read/write `IOException`s are reported as `RetryableSerDesException`. Wrap a SerDes with `RetrySerDes` to
apply any existing `RetryStrategy`:

```java
var retryingSerDes = new RetrySerDes(
        fileSystemSerDes,
        RetryStrategies.exponentialBackoff(
                4,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                2.0,
                JitterStrategy.FULL));
```

Only `RetryableSerDesException` is retried. Permanent `SerDesException` failures—malformed envelopes, invalid digests,
incompatible data, symbolic-link violations, non-directory path components, or access-denied failures—fail
immediately. Retry delays block the calling thread or the configured SerDes executor thread.

## Testing

`LocalDurableTestRunner` preserves the configured SerDes executor and creates a fresh `SerDesRunner` for each simulated
Lambda invocation. `TestResult.getResult()` and `TestOperation.getStepResult()` deserialize using durable contexts and
the invocation cache.

`CloudDurableTestRunner` and `AsyncExecution` reconstruct the execution and operation contexts from history events.
Each asynchronous history snapshot receives its own cache so updated payloads cannot reuse values from an earlier
snapshot.

See the runnable
[FileSystemSerDesExample](../../examples/src/main/java/software/amazon/lambda/durable/examples/general/FileSystemSerDesExample.java)
for filesystem configuration, structured previews, retries, replay, and checksum verification.
