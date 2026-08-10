# Custom Extension Operations Design

## Objective

Provide a supported public API for implementing reusable durable extension operations in a separate Maven module.
Extensions compose SDK-owned primitive operations without adding backend operation types, sending raw checkpoint
updates, or depending on SDK implementation classes.

Application code calls ordinary static extension methods:

```java
import static software.amazon.lambda.durable.dag.DagOperations.dag;

var result = dag("etl", () -> {
    var dag = DagContext.getCurrentContext();
    // Define DAG nodes through the current DAG context.
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

`DurableCoreOperations` exposes context-free static facades for these operations. The facades obtain the active
`DurableContext` from SDK-managed current-context storage and delegate to the existing instance methods. User
functions in the new APIs do not receive SDK context objects. For example, static step methods accept `Supplier<T>`;
step code obtains `StepContext` through `StepContext.getCurrentContext()`.

### Extension operations

Extension operations compose core operations:

- `waitForCallback`
- `waitForCondition`
- `withRetry`
- `map`
- `parallel`
- third-party operations such as DAG

Each built-in extension has an independently maintained static facade:

- `DurableMapOperations`
- `DurableParallelOperations`
- `DurableWaitForCallbackOperations`
- `DurableWaitForConditionOperations`
- `DurableWithRetryOperations`

Each class owns only its operation's overloads, tests, and documentation. Existing `DurableContext` instance methods
and their behavior remain unchanged.

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
public static DagResult dag(String name, Runnable register) {
    var extension = ExtensionContext.getCurrentContext();
    var dag = new DagContext(name, extension);
    try (var ignored = DagContext.attach(dag)) {
        register.run();
    }
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
var transformFuture = transform.stepAsync(String.class, () -> transformData());
var extractFuture = extract.runInChildContextAsync(
        ExtractResult.class,
        () -> executeExtraction());
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
- map item functions
- wait-for-callback submitters
- with-retry bodies

`DurableContext.getCurrentContext()` continues to use its existing signature. Its failure behavior is clarified:

- Handler or child-context thread: returns the active durable context.
- Step thread: throws `IllegalStateException` directing callers to `StepContext`.
- Unsupported or application-created thread: throws `IllegalStateException` explaining that no durable context is
  active.

`ExtensionContext.getCurrentContext()` returns the active extension-capable handler or child context. It throws a
clear `IllegalStateException` from step threads and unsupported threads.

Current context is not propagated to application-created threads. Extensions must create durable primitives on
SDK-managed durable context threads.

## User Function Signatures

New static APIs and extension reservations never pass SDK-created context or metadata values as user-function
arguments. User functions receive only values supplied by the application or values from the application's durable
data flow.

Examples:

```java
var result = DurableCoreOperations.step(
        "process",
        Result.class,
        () -> {
            var step = StepContext.getCurrentContext();
            return process(step.getAttempt());
        });
```

```java
var result = DurableMapOperations.map(
        "process",
        items,
        Result.class,
        item -> {
            var mapItem = MapItemContext.getCurrentContext();
            return process(item, mapItem.getIndex());
        });
```

```java
var result = DurableWaitForCallbackOperations.waitForCallback(
        "approval",
        Approval.class,
        () -> {
            var callback = WaitForCallbackContext.getCurrentContext();
            submitApproval(callback.getCallbackId());
        });
```

```java
var result = DurableWithRetryOperations.withRetry(
        "transaction",
        () -> {
            var retry = WithRetryContext.getCurrentContext();
            return executeAttempt(retry.getAttempt());
        });
```

The new callback shapes are:

- step and child-context functions: `Supplier<T>`
- map item functions: `Function<I, O>`; `MapItemContext` exposes the item index
- parallel branch functions: `Supplier<T>`
- wait-for-callback submitters: `Runnable`; `WaitForCallbackContext` exposes the callback ID
- wait-for-condition checks: receive only the durable state value; attempt metadata is available from
  `StepContext.getCurrentContext()`
- with-retry bodies: `Supplier<T>`; `WithRetryContext` exposes the attempt number
- extension child-context reservations: `Supplier<T>`; `ExtensionContext` is obtained through TLS

`MapItemContext`, `WaitForCallbackContext`, `WithRetryContext`, and any equivalent operation-specific context provide
`getCurrentContext()` static accessors. Each accessor fails clearly outside its matching user-function scope. These
operation-specific contexts are bound in addition to the base durable or step context so static core operations
continue to resolve the active `DurableContext` or `StepContext`.

The initial operation-specific metadata contracts are:

```java
public interface MapItemContext {
    static MapItemContext getCurrentContext() {
        return OperationContextStorage.get(MapItemContext.class);
    }

    int getIndex();
}

public interface WaitForCallbackContext {
    static WaitForCallbackContext getCurrentContext() {
        return OperationContextStorage.get(WaitForCallbackContext.class);
    }

    String getCallbackId();
}

public interface WithRetryContext {
    static WithRetryContext getCurrentContext() {
        return OperationContextStorage.get(WithRetryContext.class);
    }

    int getAttempt();
}
```

Each context uses a scoped SDK-managed `ThreadLocal`. Entering a nested operation stores the previous value, and
closing the scope restores it. The thread-local value is removed when no previous value exists.
`OperationContextStorage` is a package-private SDK implementation detail.

Existing context-accepting functions on `DurableContext`, including `Function<StepContext, T>`,
`Function<DurableContext, T>`, and existing map/retry callback types, remain unchanged for backward compatibility.

## Static Operation Facades

`DurableCoreOperations` contains only core operations. Each method obtains the current durable context internally.
Its step and child-context static methods accept context-free suppliers. Code inside those callbacks uses typed
current-context accessors when it needs SDK metadata.

Each built-in extension facade contains only one operation family:

| Facade | Methods |
| --- | --- |
| `DurableMapOperations` | `map`, `mapAsync` |
| `DurableParallelOperations` | `parallel` and its branch-building API |
| `DurableWaitForCallbackOperations` | `waitForCallback`, `waitForCallbackAsync` |
| `DurableWaitForConditionOperations` | `waitForCondition`, `waitForConditionAsync` |
| `DurableWithRetryOperations` | `withRetry`, `withRetryAsync` |

The initial implementations delegate to the existing `DurableContext` methods to preserve behavior. The
classification and static API do not require rewriting each established operation implementation in this change.
Each facade is a stateless utility class. Calling a facade outside a supported durable context produces the same clear
failure as `DurableContext.getCurrentContext()`.

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

- `DurableCoreOperations`
- `DurableMapOperations`
- `DurableParallelOperations`
- `DurableWaitForCallbackOperations`
- `DurableWaitForConditionOperations`
- `DurableWithRetryOperations`
- `ExtensionContext`
- `ExtensionOperation`
- `MapItemContext`
- `WaitForCallbackContext`
- `WithRetryContext`
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
- operation-specific context lookup and scope validation
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
- context-free user functions with TLS-based metadata access
- primitive plugin lifecycle events

Formatting runs through `mvn spotless:apply`. Verification starts with focused SDK and integration tests, then expands
to the full reactor because the change affects public APIs, execution context propagation, replay identity, and
durable futures.
