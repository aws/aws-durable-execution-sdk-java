# Custom Extension Operations Design

## Objective

Provide a supported public API for implementing reusable durable extension operations in a separate Maven module.
Extensions compose SDK-owned primitive operations without adding backend operation types, sending raw checkpoint
updates, or depending on SDK implementation classes.

Application code calls ordinary static extension methods:

```java
import static software.amazon.lambda.durable.dag.DagOperations.dag;

var result = dag("etl", definition -> {
    // Define DAG nodes.
});
```

The public `DurableContext` interface remains unchanged. Its existing instance methods continue to work for backward
compatibility.

## Operation Categories

### Core operations

Core operations correspond to SDK-owned primitive behavior:

- `step`
- `wait`
- chained `invoke`
- `createCallback`
- `runInChildContext`

`DurableOperations` exposes context-free static facades for these operations. The facades obtain the active
`DurableContext` from SDK-managed current-context storage and delegate to the existing instance methods. New static
step APIs use `StepContext` functions and do not reproduce the deprecated `Supplier` step overloads.

### Extension operations

Extension operations compose core operations:

- `waitForCallback`
- `waitForCondition`
- `withRetry`
- `map`
- `parallel`
- third-party operations such as DAG

`DurableExtensionOperations` exposes the built-in extension operations through the same static-import style.
Existing `DurableContext` instance methods and their behavior remain unchanged.

An extension does not automatically create a child context. Each extension chooses its scope:

- Replay-safe value helpers can create a step directly in the current context.
- Recursive invocation helpers can delegate directly to `invoke`.
- `map`, `parallel`, or `withRetry` can explicitly create child contexts when isolation is part of their semantics.
- DAG can reserve primitive identities in the current scope or explicitly create a child context if the DAG contract
  requires one.

## Extension Authoring Contract

There is no universal `DurableExtensions.run` or `runAsync` method. An extension is an ordinary public static method
that uses the static operation facades and, when it needs stable deferred identities, the active `ExtensionContext`.

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

SDK-managed handler and child contexts implement both `DurableContext` and `ExtensionContext`. Step contexts do not.
`ExtensionContext.getCurrentContext()` therefore succeeds only on supported handler and child-context threads.

`ExtensionContext` exposes metadata through `BaseContext`, replay state, and deterministic primitive reservations. It
does not expose execution managers, checkpoint models, backend operation types, operation updates, or raw operation
IDs.

## Primitive Reservations

`ExtensionContext.reserve(name)` immediately consumes the next sequential operation ID in the active durable scope and
returns an opaque, one-shot `ExtensionOperation`. The ID remains hidden from extension code.

```java
public interface ExtensionOperation {
    <T> DurableFuture<T> stepAsync(
            TypeToken<T> resultType,
            Function<StepContext, T> function,
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
            Function<ExtensionContext, T> function,
            RunInChildContextConfig config);

    // Class<T>, synchronous, and default-configuration overloads are default methods.
}
```

The operation name is bound by `reserve` and is not repeated when selecting the primitive. A reservation can execute
exactly one primitive operation. Reuse fails with `IllegalStateException`.

The SDK implements reservations by allocating an ID through the current context's normal `OperationIdGenerator`.
Package-private explicit-ID variants of primitive creation methods consume the reserved ID. These internal methods are
not part of the extension API.

## DAG Usage

A DAG module uses only public SDK contracts:

```java
public static DagResult dag(String name, Consumer<DagContext> register) {
    var extension = ExtensionContext.getCurrentContext();
    var dag = new DagContext(name, extension);
    register.accept(dag);
    return dag.execute();
}
```

During its deterministic definition phase, the DAG reserves primitive positions:

```java
var extract = extension.reserve("extract");
var transform = extension.reserve("transform");
var load = extension.reserve("load");
```

The scheduler can later execute those reservations in any dependency-valid order:

```java
var transformFuture = transform.stepAsync(String.class, step -> transformData());
var extractFuture = extract.runInChildContextAsync(
        ExtractResult.class,
        child -> executeExtraction());
```

Registration order determines IDs; launch order does not. This supports graph scheduling without name-derived IDs or
public explicit-ID APIs.

The production DAG module is outside this issue. A small extension fixture in a separate repository Maven module
proves that an external module can compile and execute using only the supported contracts.

## Current Context

The SDK binds and restores current context around:

- the durable handler
- child-context functions
- step functions
- wait-for-condition check functions

`DurableContext.getCurrentContext()` continues to use its existing signature. Its failure behavior is clarified:

- Handler or child-context thread: returns the active durable context.
- Step thread: throws `IllegalStateException` directing callers to `StepContext`.
- Unsupported or application-created thread: throws `IllegalStateException` explaining that no durable context is
  active.

`ExtensionContext.getCurrentContext()` returns the active extension-capable handler or child context. It throws a
clear `IllegalStateException` from step threads and unsupported threads.

Current context is not propagated to application-created threads. Extensions must create durable primitives on
SDK-managed durable context threads.

## Static Operation Facades

`DurableOperations` contains only core operations. Each method obtains the current durable context internally. Its
child-context static methods use callbacks that do not require callers to receive a `DurableContext`; code inside the
callback can use static operations or `ExtensionContext.getCurrentContext()`.

`DurableExtensionOperations` contains built-in composed operations. The initial implementation delegates to the
existing `DurableContext` methods to preserve behavior. The classification and static API do not require rewriting
each established operation implementation in this change.

Both facade classes are stateless utility classes. Calling either facade outside a supported durable context produces
the same clear failure as `DurableContext.getCurrentContext()`.

## Durable Futures

Asynchronous extension methods may return SDK operation futures or custom composed `DurableFuture` implementations.
`DurableFuture` therefore exposes a public non-mutating completion signal:

```java
default CompletableFuture<Void> completionFuture() {
    throw new UnsupportedOperationException(
            "This DurableFuture does not expose a completion signal");
}
```

SDK operation implementations return a derived completion future whose completion or cancellation cannot mutate the
durable operation. `DurableFuture.anyOf` uses this public contract instead of downcasting to
`BaseDurableOperation`. Custom futures that support `anyOf` override `completionFuture()`.

## Replay and Compatibility

Reservations must be created in the same deterministic order on every replay. Reordering, inserting, or removing
reservations can associate existing checkpoints with different logical primitives and is a workflow compatibility
change. After registration, executing reservations in a different order is supported.

Direct static core calls allocate IDs when invoked, matching existing `DurableContext` semantics. They are appropriate
when call order is deterministic.

Nested extension calls execute in the active scope unless an extension explicitly creates a child context. No
extension-specific recursion limit is introduced.

Public compatibility guarantees apply to:

- `DurableOperations`
- `DurableExtensionOperations`
- `ExtensionContext`
- `ExtensionOperation`
- `DurableFuture.completionFuture()`

Compatible SDK releases may add new default overloads or new primitive capabilities. Existing reservation ordering,
one-shot behavior, and primitive semantics change only in a breaking release.

## Plugins and Failures

There is no automatic extension lifecycle boundary because an extension is an ordinary composition method. Plugins
observe every primitive created by the extension. If the extension explicitly creates a child context, plugins also
observe that child-context operation.

Primitive serialization, exception, suspension, cancellation, retry, and checkpoint behavior remain owned by the
existing primitive implementation. Extension code cannot send checkpoint updates or define backend operation
subtypes.

Invalid names and null arguments use the existing SDK validators. Reusing a reservation or requesting current context
from an unsupported thread fails before creating a primitive.

## Verification

Unit tests cover:

- current durable, step, and extension context lookup
- restoration of nested current-context bindings
- deterministic reservation allocation
- out-of-order reservation execution
- one-shot reservation enforcement
- each reserved primitive delegation path
- custom `DurableFuture` participation in `anyOf`
- static facade failure outside a durable context

Integration tests in `sdk-integration-tests` cover:

- an extension fixture compiled in a separate Maven module
- initial execution and replay
- suspension and resume
- reservations launched in different orders across replays
- nested extensions in the same scope
- extensions that explicitly create child contexts
- static core and built-in extension facades
- primitive plugin lifecycle events

Formatting runs through `mvn spotless:apply`. Verification starts with focused SDK and integration tests, then expands
to the full reactor because the change affects public APIs, execution context propagation, replay identity, and
durable futures.
