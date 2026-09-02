# ADR-006: Java Payload Offloading Architecture

**Status:** Accepted

**Date:** 2026-09-02

**Related issue:** [#463](https://github.com/aws/aws-durable-execution-sdk-java/issues/463)

## Purpose

The Java SDK needs to persist user payloads that may exceed the durable execution checkpoint limit. The first storage backend is a shared filesystem such as EFS, but the architectural decision affects more than filesystem I/O:

- public extension points;
- replay and retry safety;
- checkpoint wire formats;
- chained-invoke interoperability;
- caching and concurrency;
- future storage backends;
- compatibility with existing `SerDes` implementations.

This document compares three design options in the following order:

1. context-aware `SerDes` with `FileSystemSerDes`;
2. a composable `SerDes` pipeline with `FileSystemSerDesStage`;
3. a dedicated `PayloadOffloader` abstraction.

The decision prioritizes an idiomatic Java API, compatibility with existing Java applications, and clear ownership of
serialization and storage concerns. Python and JavaScript SDKs may retain different APIs when those APIs are more
idiomatic for their languages.

## Requirements

Any selected design must satisfy these requirements:

1. **Replay safety:** Checkpointed references must remain immutable or versioned, including when a later publication
   succeeds but its checkpoint fails.
2. **Identity and security:** References must bind producer execution, entity, payload kind, attempt, and content digest.
   Filesystem access must reject traversal, symlinks, substitution, and tampering.
3. **Compatibility:** Existing and custom `SerDes` behavior, including specialized trigger-event deserialization, must
   continue to work. Ordinary Lambda payloads must remain compatible by default.
4. **Envelope correctness:** Formats must be versioned and fail closed. `OVERFLOW` decisions must use the final encoded
   envelope size, including escaping and previews.
5. **Failure semantics:** Serialization, storage, envelope, integrity, and checkpoint failures must remain distinct.
   Storage failures must not become user-function outcomes, and checkpointed user failures must replay unchanged.
6. **Retries and cleanup:** Only transient storage failures should receive bounded retries. Permanent failures must fail
   immediately, interruption must be preserved, incomplete writes must be cleaned up, and failed cache entries must be
   retryable.
7. **Cross-execution behavior:** Chained-invoke payloads and results must preserve producer ownership and use explicit
   compatibility controls when external storage is involved.
8. **Fast paths:** Disabled offloading must add no storage calls or envelope encoding. Inline payloads must avoid
   external I/O and mandatory executor dispatch, while minimizing copies and repeated UTF-8 conversion.
9. **Caching and memory:** Concurrent reads must share in-flight loading and deserialization. Invocation caches must be
   bounded and avoid retaining user values or duplicate payload strings indefinitely.
10. **Executor isolation:** Blocking storage work may use a dedicated executor, but must not deadlock user-operation or
    SDK coordination executors. Independent payloads must continue concurrently.
11. **Operations and validation:** Errors must include payload identity without exposing sensitive data. Documentation
    and tests must cover storage durability, retention, inline and overflow paths, replay, concurrency, retries, and
    executor saturation.

## Starting Point: `SerDes`

The existing public contract is intentionally small:

```java
public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, TypeToken<T> typeToken);
}
```

This contract models value encoding:

```
Object <-> serialized String
```

It does not model:

- whether the string should remain inline or be stored externally;
- the external reference and its ownership;
- preview metadata;
- storage retries;
- storage lifecycle;
- the distinction between direct Lambda input and persisted durable state.

The missing durable payload identity can be added to `SerDes`, but doing so does not resolve the responsibility question: should an object codec also own payload storage?

## Existing Custom `SerDes` Users

Many applications already configure a custom `SerDes`. Issue
[#366](https://github.com/aws/aws-durable-execution-sdk-java/issues/366) documents one important example: trigger
events such as `SQSEvent` may require a customized `JacksonSerDes`, `ObjectMapper`, or event-aware codec to reproduce
the AWS Lambda Java event deserialization behavior. The configured `SerDes` may also be inherited by steps, callbacks,
child contexts, and other operations.

Payload offloading should not require these users to replace or duplicate their working serialization configuration:

- With a context-aware `FileSystemSerDes`, the custom `SerDes` must become its delegate, and every applicable override
  must consistently select the wrapper.
- With a composable pipeline, the custom `SerDes` must become the root codec, and the application must rebuild its
  configuration as a pipeline with the filesystem stage.
- With a dedicated `PayloadOffloader`, the existing custom `SerDes` remains unchanged. The application adds filesystem
  offloading as an independent configuration concern.

The dedicated offloader is therefore the best option for existing custom-`SerDes` users. It preserves trigger-event
and domain serialization behavior while adding external storage after serialization.

## Option 1: Context-Aware `SerDes` and `FileSystemSerDes`

### Design

This option keeps the original methods and adds backward-compatible default overloads:

```java
public interface SerDes {
    String serialize(Object value);

    default String serialize(Object value, SerDesContext context) {
        return serialize(value);
    }

    <T> T deserialize(String data, TypeToken<T> typeToken);

    default <T> T deserialize(
            String data,
            TypeToken<T> typeToken,
            SerDesContext context) {
        return deserialize(data, typeToken);
    }
}
```

The context is deliberately small:

```java
public record SerDesContext(
        String durableExecutionArn,
        String entityId) {}
```

An invocation-scoped `SerDesRunner` passes the context explicitly, optionally dispatches work to a dedicated executor, deduplicates in-flight deserializations, and caches successful values for the invocation. Existing `SerDes` implementations remain compatible because the default overloads call the original methods.

`FileSystemSerDes` combines value encoding and external storage:

```
Object
  -> delegate SerDes
  -> serialized String
  -> inline filesystem envelope or file-reference envelope
  -> checkpoint String
```

On deserialization, it recognizes its versioned marker, loads and verifies referenced content when needed, and then delegates value decoding.

The design uses existing global and per-operation `SerDes` selection. An invoke can select `FileSystemSerDes` independently for its request and result. Offloaded chained-invoke payloads and results require compatible `FileSystemSerDes` configuration and access to the same shared filesystem.

### Advantages

- It is the smallest of the three designs.
- Existing implementations normally require no changes because the contextual overloads have default implementations.
  Rare signature or inherited-default conflicts may require changes.
- Context is explicit; the design does not rely on thread-local state.
- It reuses the existing global and per-operation `SerDes` configuration model.
- It provides a direct path to filesystem parity without introducing a second top-level extension point.
- `FileSystemSerDes` can safely implement immutable publication, integrity checks, previews, retries, and caching.
- Customers can keep custom value encoding by configuring a delegate `SerDes`.

### Disadvantages

- It combines object encoding and payload storage. A change to either concern can affect configuration, persisted wire
  formats, failure classification, testing, and replay compatibility for both.
- It is suited to near-term filesystem support, but not to a long-term, multi-backend payload-storage architecture.
- The SDK still sees the filesystem envelope as an opaque serialized string.
- Storage size decisions, ownership, preview behavior, and reference lifecycle belong to each storage-oriented `SerDes` implementation rather than one SDK-owned pipeline.
- A customer with a custom `SerDes` must wrap it in every external-storage implementation.
- Additional backends such as S3 or DynamoDB would likely create additional storage-specific `SerDes` wrappers with repeated envelope, retry, caching, and integrity logic.
- Adding context overloads expands the general serialization API even though only storage-aware implementations need durable payload identity.
- Chained-invoke offloading is selected through the request and result `SerDes`. It does not define a separate source
  frame or target acceptance switch, so compatibility with standard Lambda functions, older Java SDKs, and targets
  using a different payload contract is easier to misconfigure.
- It does not provide a general composition model for compression, encryption, signing, or multiple storage-related transformations.

### Why This Option Is Not Favored

The main concern is not implementation feasibility or missing filesystem behavior. This option can provide a complete and relatively small filesystem implementation. The concern is placing storage semantics inside the value codec.

`SerDes` is used to answer:

> How is this Java value represented as a string?

Payload offloading answers different questions:

> Should this serialized string remain inline, where should it be stored, how is the reference validated, and how long must that reference remain valid?

Keeping those questions in one abstraction makes the first filesystem implementation straightforward, but makes the SDK less able to enforce uniform storage behavior as new backends and cross-execution use cases are added. It also means that checkpoint envelope ownership is distributed among `SerDes` implementations.

For those reasons, the context-aware `SerDes` option is a good narrow design but is not the preferred long-term payload-storage architecture.

## Option 2: Composable `SerDes` Pipeline

### Design

This option keeps the root value codec context-free and adds reversible string stages:

```java
public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, TypeToken<T> typeToken);

    default SerDes then(SerDesStage nextStage) {
        return ComposableSerDes.builder(this)
                .then(nextStage)
                .build();
    }
}

public interface SerDesStage {
    String serialize(String value, SerDesContext context);

    String deserialize(String data, SerDesContext context);
}
```

The pipeline contains exactly one root value codec followed by zero or more string stages:

```
Object
  -> root SerDes
  -> String
  -> SerDesStage 1
  -> String
  -> SerDesStage 2
  -> persisted String
```

Deserialization applies the stages in reverse order before calling the root codec.

`FileSystemSerDesStage` is one possible stage. The design also includes:

- binary substages for compression, encryption, and similar byte transformations;
- retry decorators for string and binary stages;
- explicit rich `SerDesContext` propagation to stages;
- `originalValue` access during serialization for preview generation;
- framed, two-sided opt-in for persisted chained-invoke payloads.

Every stage must produce a self-identifying format. During deserialization, a stage must:

1. reverse input in its recognized format;
2. reject recognized malformed or unsupported input;
3. pass unrecognized input through unchanged.

This allows ordinary external input to pass through a persisted pipeline and reach the root value codec.

### Advantages

- It provides the most general extension model.
- Durable payload context is explicit and does not require thread-local state.
- Customers can compose value encoding, compression, encryption, signing, retries, and external storage.
- Stage order is visible and controlled by the application.
- Binary transformations can remain binary inside one composite stage instead of repeatedly converting through text.
- A filesystem stage can be placed before or after other reversible transformations.
- The framed chained-invoke protocol preserves ordinary Lambda payload behavior by default and requires explicit caller and target opt-in.
- The model is useful beyond filesystem storage if a general persisted-data transformation pipeline is an intended SDK feature.

### Disadvantages

- It has the largest public API and implementation surface.
- It commits the SDK to a general transformation framework while the immediate requirement is payload offloading.
- Stage ordering and configuration become part of the durable checkpoint compatibility contract.
- Each stage owns and versions its own format; there is no single SDK-owned envelope describing the complete pipeline.
- The self-identifying pass-through rule is subtle and must be implemented correctly by every stage.
- A stage can transform a filesystem reference envelope after offloading, which makes final checkpoint sizing and observability dependent on the remaining pipeline.
- The root value codec remains context-free while stages receive context, creating two different extension contracts.
- Applications with a custom `SerDes` must rebuild their configuration as a pipeline rooted at that codec and keep any
  independently configured input codec aligned.
- Initial invocation input requires a separate context-free codec because the persisted pipeline may require execution identity that does not exist at submission time.
- Operation-level selection replaces the complete pipeline rather than composing global and local stages.
- The amount of API surface, documentation, and compatibility policy is high for customers who only need large-payload storage.

### Assessment

The composable pipeline is the strongest option if the Java SDK intentionally wants to expose a general serialization-transformation pipeline as a first-class product feature.

It is less attractive if the primary objective is a stable payload-storage abstraction. Compression, encryption, and storage do not necessarily have the same lifecycle, failure classification, or checkpoint semantics. Treating them all as reversible stages makes composition powerful, but also distributes responsibility for persisted formats and final payload sizing.

The pipeline can coexist with a future offloader, but adopting it solely to solve filesystem storage would make a larger public commitment than the current requirement needs.

## Option 3: Dedicated `PayloadOffloader`

### Design

This option leaves `SerDes` responsible for value encoding and adds a separate storage extension point:

```java
public interface PayloadOffloader {
    OffloadedPayload offload(
            String serializedPayload,
            PayloadOffloadContext context);

    String load(
            OffloadedPayload payload,
            PayloadOffloadContext context);
}
```

The runtime pipeline becomes:

```
Object
  -> SerDes
  -> serialized String
  -> PayloadOffloader
  -> SDK-owned payload envelope
  -> checkpoint String
```

Replay reverses the flow:

```
checkpoint String
  -> SDK-owned payload envelope
  -> PayloadOffloader.load
  -> serialized String
  -> SerDes
  -> Object
```

The SDK-owned `OffloadedPayload` model distinguishes inline data from an external reference and carries preview, producer ownership, content-digest, producer-context, and load-semantics metadata. `PayloadOffloadContext` explicitly provides execution identity, payload kind, operation metadata, attempt, and the original serialization value when available.

The offloader can be configured globally or overridden per operation. Operation builders provide an explicit
`disablePayloadOffloading()` method when an operation must bypass a global offloader. Serialization remains
independently configurable through existing `SerDes` options.

The SDK owns:

- envelope encoding and versioning;
- producer ownership and cross-execution validation;
- final envelope sizing;
- loaded-string and deserialized-object caches;
- failure classification at the payload-storage boundary;
- framed chained-invoke request, result, and error handling;
- precedence between global, operation-specific, and disabled offloaders.

The filesystem implementation owns:

- inline-versus-reference selection;
- filesystem path construction;
- immutable publication;
- secure loading;
- preview generation;
- backend-specific retryable failures.

Direct Lambda input and externally submitted callback results retain their existing `SerDes` wire contracts. Offloaded chained invokes require an explicit caller opt-in and target acceptance setting, preserving compatibility with standard Lambda functions and older SDKs by default.

### Advantages

- It preserves the meaning and API of `SerDes` as object-to-string encoding.
- It gives payload storage a clear, dedicated responsibility.
- The SDK owns one versioned envelope and can apply uniform validation across storage backends.
- Storage backends share the same ownership, digest, preview, caching, retry, and chained-invoke semantics.
- Serialized text size and final checkpoint envelope size can be reasoned about separately.
- Applications can add filesystem offloading without modifying, wrapping, or rebuilding an existing custom `SerDes`
  configuration; value encoding and offloading are selected independently.
- Global, per-operation, and disabled precedence is explicit.
- The two-sided chained-invoke protocol keeps ordinary Lambda interoperability as the default.
- Producer context can be retained when payloads cross execution boundaries, allowing context-keyed custom storage to load the original reference correctly.
- Future filesystem, object-store, or database implementations can use the same extension point.

### Disadvantages

- It introduces a new public abstraction and configuration dimension.
- The runtime must thread an offloader through root output, operation results, exceptions, concurrency operations, invoke boundaries, testing utilities, and cloud-history inspection.
- The SDK commits to owning and evolving the payload envelope wire format.
- Applications must coordinate `SerDes` and `PayloadOffloader` selection across global and per-operation inheritance,
  override, and disable rules.
- It is intentionally narrower than the composable pipeline: it does not provide a general compression or encryption stage pipeline.
- Compatible offloaded chained invokes still require shared storage or another backend accessible to both executions.

### Assessment

The dedicated offloader adds more architecture than the context-aware `SerDes` option, but the added abstraction matches the actual responsibility being introduced. It is smaller in concept than the composable pipeline because it does not attempt to generalize every persisted-data transformation.

The core tradeoff is accepting one new extension point in exchange for keeping storage lifecycle, checkpoint compatibility, and cross-execution behavior under SDK control.

## Cross-Cutting Behavior

The selected architecture should include the following common correctness requirements:

### Immutable publication

Every external reference stored in a checkpoint must remain immutable for the lifetime of that checkpoint. Filesystem payloads should use unique `CREATE_NEW` publication and must not overwrite a stable path after a retry or checkpoint failure.

### Ownership and integrity

References should bind:

- producer durable execution;
- producer entity;
- exact serialized-content digest.

Cross-execution boundaries must preserve producer ownership rather than rewriting a reference as if the consumer had created it.

### Secure filesystem access

The filesystem backend should:

- require a pre-provisioned base directory;
- resolve paths relative to held secure directory handles;
- reject traversal and paths outside the base directory;
- reject symbolic links;
- use `NOFOLLOW_LINKS`;
- verify the serialized-content digest;
- fail closed when the filesystem provider cannot provide the required security guarantees.

### Invocation-scoped caching

Concurrent loads and deserializations should share in-flight work. Completed caches should be bounded and include implementation identity, durable payload identity, target type, and payload hash so updated operation state cannot return a stale value.

### Executor isolation

Payload I/O may execute inline by default with an optional dedicated executor. It must not reuse a saturated user-operation executor when the operation synchronously waits for payload processing.

### Chained invokes

Ordinary Lambda payloads should remain the default. An offloaded request should use a versioned source frame and
two-sided opt-in so a standard function, an older SDK, or a target using a different payload contract does not receive
an opaque storage envelope accidentally.

## Comparison Summary

| Dimension | Context-aware `SerDes` | Composable pipeline | `PayloadOffloader` |
| --- | --- | --- | --- |
| Primary abstraction | Storage-aware value codec | Reversible string stages | Dedicated serialized-payload storage |
| Existing `SerDes` methods | Preserved; contextual overloads added | Preserved; `then(...)` added | Unchanged |
| Context recipient | Entire `SerDes` | Pipeline stages only | Offloader |
| Serialization/storage separation | Low | Medium | High |
| General transformation composition | Low | High | Low by design |
| Impact on an existing custom `SerDes` | Wrap as the filesystem delegate | Rebuild as the pipeline root | Keep unchanged; configure offloading separately |
| Storage backend reuse | Wrapper per backend | Stage per backend | One common offloader contract |
| Envelope ownership | Storage `SerDes` | Individual stages | SDK |
| Final checkpoint-size ownership | Storage `SerDes` | Distributed across stages | SDK pipeline |
| Chained-invoke request model | Direct compatible `SerDes` selection | Framed, two-sided opt-in | Framed, two-sided opt-in |
| Relative implementation scope | Smallest | Largest | Middle |
| Best fit | Narrow filesystem parity | General transformation framework | Long-term payload storage |

## Summary

The context-aware `SerDes` option is the most direct design. It solves filesystem storage with explicit context and minimal disruption to existing configuration. Its weakness is architectural coupling: serialization implementations become responsible for storage policy and checkpoint reference semantics.

The composable pipeline is the most extensible design. It is appropriate if the Java SDK wants a general, ordered, reversible transformation pipeline. Its weakness is the size of the public commitment and the distributed ownership of persisted stage formats.

The dedicated offloader introduces an SDK-owned envelope. It adds a new extension point, but keeps value encoding and payload storage separate and gives the SDK one place to enforce replay, ownership, sizing, caching, retry, and cross-execution rules.

## Decision

The Java SDK will adopt the dedicated `PayloadOffloader` architecture.

The decision is based on the following:

1. **Idiomatic Java API:** A small, explicit strategy interface, typed context, immutable models, and builder-based
   configuration follow established Java API patterns.
2. **Responsibility alignment:** `SerDes` encodes values; `PayloadOffloader` stores serialized payloads.
3. **Uniform durable semantics:** The SDK can own envelope versioning, producer ownership, content integrity, replay
   compatibility, and final checkpoint sizing.
4. **Backend growth:** Additional storage backends can share one contract without adding more storage-oriented
   `SerDes` wrappers.
5. **Invoke compatibility:** Framed, two-sided chained-invoke opt-in preserves ordinary Lambda behavior by default.
6. **Controlled scope:** The design solves payload storage without committing the SDK to the broader composable
   transformation framework.
7. **Custom serializer preservation:** Applications using specialized trigger-event or domain serializers can add
   filesystem offloading without modifying or wrapping their existing `SerDes`.

### Language-specific SDK direction

This ADR applies to the Java SDK. Python and JavaScript are not required to introduce `PayloadOffloader` or mirror the
Java API. They may retain filesystem `SerDes` APIs when those APIs remain idiomatic for their languages.

Cross-language API consistency is not a decision criterion. Shared behavior or wire formats should be standardized only
when a concrete interoperability requirement exists. The Java SDK should not couple storage to `SerDes` solely to
match another language's API shape.
