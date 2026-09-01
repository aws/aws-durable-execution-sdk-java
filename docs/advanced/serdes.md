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

SDK-managed calls expose durable identity through thread-local storage without changing the `SerDes` interface:

```java
var context = SerDesContext.getCurrentContext();
if (context != null) {
    var executionArn = context.durableExecutionArn();
    var entityId = context.entityId();
}
```

The context is installed only while the SDK invokes `serialize` or `deserialize` and is restored in `finally`.
Direct customer calls return `null`.

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
target type, and serialized payload hash.

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
capability fail closed.

Only objects containing the reserved version marker are treated as filesystem envelopes. Ordinary JSON with `data` or
`file` fields is passed to the configured delegate.

```json
{"__durable_execution_filesystem_serdes":1,"data":"<delegate payload>"}
{"__durable_execution_filesystem_serdes":1,"file":"/mnt/efs/...json","sha256":"<digest>"}
```

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

The durable execution ARN does not exist before the initial Lambda invocation starts. A direct
`FileSystemSerDes.serialize()` call therefore delegates normally when no `SerDesContext` exists. Root input is ordinary
delegate JSON; SDK-managed output and operation payloads can use filesystem storage after the invocation begins.

### Operational requirements

- Do not use Lambda `/tmp`; replay may run in another execution environment.
- Use a shared durable mount such as EFS.
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
or incompatible data—fail immediately. Retry delays block the calling thread or the configured SerDes executor thread.

## Testing

`LocalDurableTestRunner` preserves the configured SerDes executor and creates a fresh `SerDesRunner` for each simulated
Lambda invocation. `TestResult.getResult()` and `TestOperation.getStepResult()` deserialize using durable contexts and
the invocation cache.

`CloudDurableTestRunner` and `AsyncExecution` reconstruct the execution and operation contexts from history events.
Each asynchronous history snapshot receives its own cache so updated payloads cannot reuse values from an earlier
snapshot.
