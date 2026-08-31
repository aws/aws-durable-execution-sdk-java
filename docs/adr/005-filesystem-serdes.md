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

1. dispatches the SerDes call to the configured SerDes executor;
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

The default is a shared cached daemon pool named `durable-serdes-*`. The SerDes executor must not be the same object as
the user-operation executor because operation threads synchronously wait for SerDes work and a shared saturated pool
could deadlock.

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
        .previewGenerator(value -> Map.of("type", value.getClass().getSimpleName()))
        .build();

return DurableConfig.builder()
        .withSerDes(serDes)
        .build();
```

Storage modes:

| Mode | Behavior |
| --- | --- |
| `ALWAYS` | Store every SDK-managed non-null payload in a file. |
| `OVERFLOW` | Keep the versioned envelope inline until it exceeds 255 KiB, then store the payload in a file. |

Path encodings:

| Encoding | Behavior |
| --- | --- |
| `URI` | Percent-encode readable execution and entity path segments. |
| `HASH` | Use fixed-length SHA-256 path segments. |

The default delegate is `JacksonSerDes`; a custom delegate can be supplied through the builder.

### Envelope and file publication

Java writes versioned envelopes:

```json
{"__durable_execution_filesystem_serdes":1,"data":"<delegate payload>"}
{"__durable_execution_filesystem_serdes":1,"file":"/mnt/efs/...json","sha256":"<digest>"}
{"__durable_execution_filesystem_serdes":1,"file":"/mnt/efs/...json","sha256":"<digest>","preview":{"id":"123"}}
```

The additional marker and digest are ignored by the JavaScript implementation, which reads the `data`, `file`, and
`preview` fields. Java also reads the unversioned JavaScript envelopes.

File names include the entity ID and serialized-payload digest. Files are created with `CREATE_NEW`; an existing file is
accepted only when its contents match. This prevents a later retry from overwriting data referenced by an earlier
checkpoint. Deserialization rejects paths outside the configured base directory and verifies the digest when present.

### Initial invocation input

The durable execution ARN does not exist when a caller serializes the initial Lambda input. Therefore,
`FileSystemSerDes.serialize()` delegates directly when `SerDesContext.getCurrentContext()` is `null`. The initial input
remains ordinary delegate JSON.

After the invocation starts, the SDK routes root input deserialization, operation payloads, exceptions, and root output
through `SerDesRunner`. Chained invokes that use filesystem SerDes require the caller and callee to use compatible
configuration and have access to the same durable filesystem.

## Consequences

Positive:

- Existing custom `SerDes` implementations remain source and binary compatible.
- Filesystem and custom external-storage SerDes implementations receive stable payload identity.
- Blocking SerDes work is isolated from user and SDK coordination executors.
- Repeated deserialization and file reads are avoided within an invocation.
- The implementation uses existing global and per-operation SerDes configuration.
- Java and JavaScript filesystem envelopes are mutually readable.

Negative:

- SerDes context is implicit thread-local state.
- Every SDK-managed SerDes call crosses an executor boundary.
- Repeated deserialization returns the same object instance within an invocation.
- Filesystem retention and cleanup remain the application's responsibility.
- Chained invoke payloads and results require shared storage and compatible SerDes configuration.

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

### Run SerDes inline or omit caching

Rejected because mounted filesystem I/O can block user progress and repeated reads can repeat externally visible work
and cost.
