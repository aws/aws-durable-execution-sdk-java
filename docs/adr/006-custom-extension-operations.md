# ADR-006: Public API for Custom Extension Operations

**Status:** Accepted

**Date:** 2026-08-10

## Context

Issue [#571](https://github.com/aws/aws-durable-execution-sdk-java/issues/571) requests a supported way to
implement reusable durable operations in separate Maven modules without changing or rebuilding the core SDK.

The SDK currently exposes all operations as instance methods on `DurableContext`. This creates several constraints:

- Adding an optional operation requires changing the core `DurableContext` interface and `DurableContextImpl`.
- Extension libraries cannot create primitive operations with stable identities when registration order and execution
  order differ.
- Extension code would need SDK implementation classes or public explicit-operation-ID methods to implement schedulers
  such as DAG.
- Existing user-function APIs receive SDK-created context and metadata parameters, coupling new APIs to callback
  signatures instead of the SDK-managed current context.
- A single facade containing every built-in extension would couple unrelated operation families and make independent
  maintenance difficult.

Extension operations do not share one execution scope. Some extensions are direct primitive wrappers in the current
scope, while others deliberately create child contexts. The extension mechanism must not impose a child-context
boundary.

The SDK must continue to own primitive operation IDs, checkpointing, replay, suspension, serialization, failures, and
backend communication. This decision does not make backend operation types extensible.

The detailed API specification is in
[Custom Extension Operations Design](../superpowers/specs/2026-08-10-custom-extension-operations-design.md).

## Decision

### Preserve DurableContext

Keep the public `DurableContext` interface unchanged. Its existing instance methods and context-accepting callback
types remain supported for backward compatibility.

SDK-managed handler and child contexts additionally implement a new public `ExtensionContext` interface. Step
contexts do not implement it.

```java
public interface ExtensionContext extends BaseContext {
    static ExtensionContext getCurrentContext() {
        var context = BaseContext.getCurrentContext();
        if (context instanceof ExtensionContext extensionContext) {
            return extensionContext;
        }
        throw new IllegalStateException(
                "ExtensionContext is only available from a durable handler or child-context thread");
    }

    boolean isReplaying();

    ExtensionOperation reserve(String name);
}
```

Extension libraries expose ordinary static methods. There is no required registration mechanism and no universal
`DurableExtensions.run` method.

```java
public final class DagOperations {
    public static DagResult dag(String name, Runnable definition) {
        var extension = ExtensionContext.getCurrentContext();
        return executeDag(name, extension, definition);
    }
}
```

An extension decides whether to execute in the current scope or explicitly create a child context.

### Separate Core and Extension Facades

Expose primitive operations through `DurableCoreOperations`:

- `step`
- `wait`
- chained `invoke`
- `createCallback`
- `runInChildContext`

Expose each built-in extension family through an independently maintained class:

| Facade | Operation family |
| --- | --- |
| `DurableMapOperations` | `map`, `mapAsync` |
| `DurableParallelOperations` | `parallel` and branch construction |
| `DurableWaitForCallbackOperations` | `waitForCallback`, `waitForCallbackAsync` |
| `DurableWaitForConditionOperations` | `waitForCondition`, `waitForConditionAsync` |
| `DurableWithRetryOperations` | `withRetry`, `withRetryAsync` |

These classes obtain the active durable context from SDK-managed thread-local storage and delegate to existing
operation implementations. The facade split does not require rewriting the established operation implementations.

### Use TLS for SDK Context and Metadata

User functions in the new static APIs receive only application-provided values or values from the application's
durable data flow. They do not receive `DurableContext`, `StepContext`, `ExtensionContext`, or SDK-generated metadata
as callback parameters.

Examples:

- Step and child-context functions use `Supplier<T>`.
- Map functions receive the item; `MapItemContext.getCurrentContext()` provides the item index.
- Parallel branches use `Supplier<T>`.
- Wait-for-callback submitters use `Runnable`;
  `WaitForCallbackContext.getCurrentContext()` provides the callback ID.
- Wait-for-condition checks receive the durable state;
  `StepContext.getCurrentContext()` provides attempt metadata.
- With-retry bodies use `Supplier<T>`;
  `WithRetryContext.getCurrentContext()` provides the attempt number.

Operation-specific contexts use scoped SDK-managed thread-local storage. Nested scopes restore the previous value, and
the value is removed when no previous scope exists. Operation-specific metadata TLS is bound in addition to the base
durable or step context, allowing static core operations to resolve the correct active context.

Current context is available only on SDK-managed user-code threads. It is not propagated to application-created
threads.

### Reserve Primitive Identities

`ExtensionContext.reserve(name)` immediately allocates the next sequential operation ID in the active durable scope
and returns an opaque, one-shot `ExtensionOperation`.

```java
public interface ExtensionOperation {
    <T> DurableFuture<T> stepAsync(
            TypeToken<T> resultType,
            Supplier<T> function,
            StepConfig config);

    DurableFuture<Void> waitAsync(Duration duration);

    <T, U> DurableFuture<T> invokeAsync(
            String functionName,
            U payload,
            TypeToken<T> resultType,
            InvokeConfig config);

    <T> DurableCallbackFuture<T> createCallback(
            TypeToken<T> resultType,
            CallbackConfig config);

    <T> DurableFuture<T> runInChildContextAsync(
            TypeToken<T> resultType,
            Supplier<T> function,
            RunInChildContextConfig config);
}
```

The SDK binds the operation name and hidden ID to the reservation. The reservation can create exactly one primitive;
reusing it throws `IllegalStateException`.

Extensions with deterministic invocation order can call `DurableCoreOperations` directly. Extensions such as DAG
reserve identities during deterministic definition, then execute the reservations in any dependency-valid order.

The implementation may add package-private explicit-ID primitive constructors. Extension code cannot access those
methods or raw IDs.

### Support Composed Durable Futures

Add a public, non-mutating completion signal to `DurableFuture`:

```java
default CompletableFuture<Void> completionFuture() {
    throw new UnsupportedOperationException(
            "This DurableFuture does not expose a completion signal");
}
```

SDK operations return a derived completion future that cannot mutate the underlying durable operation.
`DurableFuture.anyOf` uses this public contract instead of downcasting to `BaseDurableOperation`. Custom composed
futures override the method when they support `anyOf`.

### Preserve Primitive Plugin Semantics

Extensions do not create an automatic lifecycle or checkpoint boundary. Plugins observe the primitives created by an
extension. If an extension explicitly creates a child context, plugins also observe that context operation.

No extension-specific backend operation type or raw checkpoint API is added.

## Alternatives Considered

### Add extension methods to DurableContext

Add `runExtensionAsync` or operation-specific methods to `DurableContext`.

**Rejected because:**

- It changes the interface that this decision must preserve.
- Optional extension families would continue to expand the core API.
- It makes extension execution appear to require a special runtime boundary.

### Require every extension to run in a child context

Provide a universal `DurableExtensions.run` method that creates a child context.

**Rejected because:**

- Direct primitive wrappers do not need a child context.
- Child-context checkpoint and replay behavior would be imposed even when it is not part of the extension semantics.
- Extensions such as map or retry must remain responsible for selecting their own isolation strategy.

### Compose directly through DurableContext only

Let extension implementations retrieve `DurableContext` and invoke its existing methods.

**Rejected because:**

- It exposes the entire legacy operation surface instead of a stable extension contract.
- Operations receive IDs when executed, so schedulers cannot register identities before varying launch order.
- Extension implementations remain coupled to context-accepting legacy callbacks.

### Expose raw or name-derived operation IDs

Allow extension libraries to supply operation IDs or derive them from operation names.

**Rejected because:**

- The SDK must retain ownership of global uniqueness and backend identity rules.
- Name-derived IDs introduce collision, normalization, and compatibility requirements.
- Public explicit-ID methods expose checkpoint protocol details.

### Pass contexts and generated metadata as callback arguments

Mirror the existing `DurableContext` callback signatures in the new static APIs.

**Rejected because:**

- The new API uses SDK-managed current context consistently across core and extension operations.
- Generated values such as map index, retry attempt, and callback ID belong to typed operation contexts.
- Context-free callbacks make extension methods compose without threading SDK objects through application code.

### Use one built-in extension facade

Place map, parallel, callback, condition, and retry methods in one `DurableExtensionOperations` class.

**Rejected because:**

- Unrelated overload sets, tests, and documentation would change together.
- Large operation families such as map and parallel need independent ownership.
- Separate classes align the public API with independently maintained extension implementations.

## Consequences

**Positive:**

- Third-party Maven modules can publish static durable operations using supported public contracts.
- Application call sites do not pass or qualify `DurableContext`.
- `DurableContext` remains source- and binary-compatible.
- Extensions choose their own scope instead of inheriting a mandatory child-context boundary.
- Deterministic reservations support replay-safe schedulers whose launch order can vary.
- Primitive IDs, checkpointing, replay, and backend communication remain SDK-owned.
- Built-in extension families can evolve independently.
- New callback APIs consistently use TLS for SDK context and generated metadata.
- Custom composed futures work with public future combinators without internal downcasts.

**Negative:**

- The SDK must manage multiple scoped thread-local context types and restore them correctly across nested calls.
- Static APIs depend on execution from SDK-managed threads and fail from application-created threads.
- Reservations add package-private explicit-ID paths that must remain consistent with ordinary primitive creation.
- The static facade API duplicates overloads that remain on `DurableContext` for compatibility.
- Extension authors must understand that reservation order is part of workflow replay compatibility.

**Compatibility requirements:**

- Reservations must be created in the same deterministic order on every replay.
- Reordering, inserting, or removing reservations can rebind existing checkpoints and is a workflow compatibility
  change.
- Launching already reserved operations in a different order is supported.
- Existing `DurableContext` methods and callback signatures remain unchanged.

**Deferred:**

- A production DAG extension module.
- Reimplementing every built-in extension through the new public reservation contract.
- Propagating current context to application-created threads.
