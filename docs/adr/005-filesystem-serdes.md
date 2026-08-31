# ADR-005: Filesystem SerDes with Thread-Local Context

**Status:** Accepted
**Date:** 2026-08-31

## Context

Issue [#463](https://github.com/aws/aws-durable-execution-sdk-java/issues/463) requests parity with the JavaScript
SDK's filesystem-backed SerDes. A filesystem SerDes needs two values that are not present in the existing Java
`SerDes` methods:

- the durable execution ARN, which isolates files belonging to different executions;
- a stable entity ID, which identifies the execution or operation payload.

The public Java interface is intentionally small and is already implemented by customers:

```java
public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, TypeToken<T> typeToken);
}
```

Changing those methods would break existing implementations. Filesystem I/O must also avoid blocking the user-operation
executor or the SDK coordination executor, and repeated `DurableFuture.get()` calls must not repeatedly read and decode
the same file.

## Decision

### Preserve the SerDes interface

The existing `SerDes` interface remains unchanged. The SDK exposes the active payload identity through:

```java
public record SerDesContext(String durableExecutionArn, String entityId) {
    public static SerDesContext getCurrentContext();
}
```

`getCurrentContext()` returns `null` outside an SDK-managed SerDes call. The SDK owns setting and clearing the context;
there is no public setter.

The implementation uses a plain `ThreadLocal`, not an `InheritableThreadLocal`. Every call restores the previous value
in `finally`, which supports nesting and prevents context from leaking when executor threads are reused.

### Use an invocation-scoped SerDesRunner

Each `ExecutionManager` creates one `SerDesRunner` for the Lambda invocation. The runner:

1. executes inline or dispatches the SerDes call to the configured SerDes executor;
2. installs `SerDesContext` inside that executor task;
3. invokes the unchanged SerDes method;
4. clears or restores the thread-local context;
5. returns the result or rethrows the original failure.

The configured executor is available through:

```java
DurableConfig.builder()
        .withSerDesExecutorService(customExecutor)
        .build();
```

SerDes calls execute inline by default. A dedicated executor is opt-in for implementations that perform blocking
storage or network I/O. When configured, it must not be the same object as the user-operation executor because operation
threads synchronously wait for SerDes work and a shared saturated pool could deadlock.

### Cache successful deserializations per invocation

`SerDesRunner` keeps up to 256 successful deserializations in a weak-reference LRU cache for the lifetime of one
`ExecutionManager`. The cache key contains:

- SerDes instance identity;
- durable execution ARN;
- entity ID;
- target `TypeToken`;
- SHA-256 of the checkpoint string.

The serialized-data hash prevents stale results when the same entity is updated, such as a retried step or
`waitForCondition` state. Concurrent callers share one in-flight deserialization. Failed deserializations are removed
from the cache and can be retried.

Repeated reads return the same object instance while the cached value remains reachable. A new invocation creates a new
runner and cache.

### Add FileSystemSerDes to the core SDK

`FileSystemSerDes` is a normal `SerDes` in `software.amazon.lambda.durable.serde`. Keeping it in the existing SDK
artifact avoids a new module and allows it to be selected globally or through existing operation-level SerDes
configuration.

```java
var serDes = FileSystemSerDes.builder(Path.of("/mnt/efs/durable-payloads"))
        .storageMode(FileSystemSerDesMode.OVERFLOW)
        .pathEncoding(FileSystemPathEncoding.HASH)
        .checkpointEnvelopeLimitBytes(512 * 1024)
        .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.anywhere("id"))
                .mask(PreviewField.anywhere("email"))
                .build())
        .build();

return DurableConfig.builder()
        .withSerDes(serDes)
        .build();
```

Storage modes:

| Mode | Behavior |
| --- | --- |
| `ALWAYS` | Store every SDK-managed non-null payload in a file. |
| `OVERFLOW` | Keep the versioned envelope inline until it exceeds the configured byte limit, then store it in a file. |

Path encodings:

| Encoding | Behavior |
| --- | --- |
| `URI` | Percent-encode readable execution and entity path segments. |
| `HASH` | Use fixed-length SHA-256 path segments. |

The default checkpoint-envelope limit is 255 KiB and can be changed with `checkpointEnvelopeLimitBytes(...)`.

The default delegate is `JacksonSerDes`; `.delegate(...)` controls how values are encoded inside files. Existing
operation-level SerDes configuration controls which boundaries use filesystem storage. For example,
`InvokeConfig.payloadSerDes(new JacksonSerDes()).serDes(fileSystemSerDes)` sends ordinary JSON while decoding the result
with filesystem storage.

Structured previews support include-all/exclude-all modes, include/exclude/mask selectors, anywhere or exact-path
matching, custom mask text, and a default 4 KiB preview budget. Custom preview callbacks remain available.

### Envelope and file publication

Java writes versioned envelopes:

```json
{"__durable_execution_filesystem_serdes":1,"data":"<delegate payload>"}
{"__durable_execution_filesystem_serdes":1,"file":"/mnt/efs/...json","sha256":"<digest>"}
{"__durable_execution_filesystem_serdes":1,"file":"/mnt/efs/...json","sha256":"<digest>","preview":{"id":"123"}}
```

Only envelopes containing the reserved version marker are interpreted as filesystem payloads. Unmarked JSON, including
objects with `data` or `file` fields, is passed to the delegate SerDes unchanged.

File names include the entity ID and serialized-payload digest. Files are created with `CREATE_NEW`; an existing file is
accepted only when its contents match. This prevents a later retry from overwriting data referenced by an earlier
checkpoint. Deserialization rejects paths outside the configured base directory and verifies the digest when present.

### Initial invocation input

The durable execution ARN does not exist when a caller serializes the initial Lambda input. Therefore,
`FileSystemSerDes.serialize()` delegates directly when `SerDesContext.getCurrentContext()` is `null`. The initial input
remains ordinary delegate JSON.

After the invocation starts, the SDK routes root input deserialization, operation payloads, exceptions, and root output
through `SerDesRunner`. A chained-invoke boundary requires compatible filesystem configuration and shared storage only
when that boundary explicitly selects `FileSystemSerDes`.

## Consequences

Positive:

- Existing custom `SerDes` implementations remain source and binary compatible.
- Filesystem and custom external-storage SerDes implementations receive stable payload identity.
- Blocking SerDes work is isolated from user and SDK coordination executors.
- Repeated deserialization and file reads are avoided within an invocation.
- The implementation uses existing global and per-operation SerDes configuration.
- Ordinary user JSON cannot be confused with a filesystem envelope.

Negative:

- SerDes context is implicit thread-local state.
- Configuring a SerDes executor adds an executor boundary to every SDK-managed SerDes call.
- Repeated deserialization returns the same object instance within an invocation.
- Filesystem retention and cleanup remain the application's responsibility.
- Chained invoke payloads and results that select filesystem storage require a shared mount and compatible paths.

## Operational Requirements

- Do not use Lambda's ephemeral `/tmp` directory. Replay may run in another execution environment.
- Use a shared durable mount such as EFS.
- S3 Files users must accept its synchronization and crash-durability characteristics.
- Configure lifecycle cleanup separately; the SDK does not delete persisted payload files.

## Alternatives Rejected

### Change the SerDes method signatures

Rejected because adding context parameters would break every existing implementation.

### Add a PayloadOffloader abstraction

Rejected for this feature because it introduces a new extension point, envelope model, precedence rules, and operation
configuration. A dedicated offloader can be reconsidered if multiple storage backends require one common lifecycle.

### Put filesystem support in a separate Maven module

Rejected for the initial implementation. The filesystem implementation uses only JDK and existing Jackson APIs, so a
new artifact would add release and documentation overhead without isolating an additional dependency.

### Run blocking filesystem work on the user or internal executor, or omit caching

Rejected. Inline execution remains the default, but applications can isolate blocking filesystem work with the
optional SerDes executor. The user-operation and internal coordination executors must not be used for that I/O.
Omitting caching would repeat file reads and object reconstruction within one invocation.
