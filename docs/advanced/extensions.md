# Custom extension operations

Extension operations are ordinary static Java methods that compose SDK-owned durable primitives. They can live in a
separate Maven module without defining backend operation types, sending checkpoint updates, or depending on SDK
implementation packages.

Application code calls only the extension's API:

```java
import static com.example.durable.PairOperations.pairAsync;

var result = pairAsync("pair", left, right).get();
```

The extension retrieves the active scope internally:

```java
public final class PairOperations {
    private PairOperations() {}

    public static DurableFuture<String> pairAsync(
            String name, Supplier<String> leftFunction, Supplier<String> rightFunction) {
        var extension = ExtensionContext.getCurrentContext();
        var left = extension.reserve(name + "-left");
        var right = extension.reserve(name + "-right");

        var leftFuture = left.stepAsync(String.class, leftFunction);
        var rightFuture = right.stepAsync(String.class, rightFunction);
        return new PairFuture(leftFuture, rightFuture);
    }
}
```

There is no extension registration API and no automatic child-context boundary. The extension chooses whether to
compose primitives in the current scope or explicitly create a child context.

## Static operation APIs

New code can use context-free static facades:

| Facade | Operations |
| --- | --- |
| `DurableCoreOperations` | `step`, `wait`, chained `invoke`, callbacks, child contexts |
| `DurableMapOperations` | `map`, `mapAsync` |
| `DurableParallelOperations` | `parallel` |
| `DurableWaitForCallbackOperations` | `waitForCallback`, `waitForCallbackAsync` |
| `DurableWaitForConditionOperations` | `waitForCondition`, `waitForConditionAsync` |
| `DurableWithRetryOperations` | `withRetry`, `withRetryAsync` |

The existing `DurableContext` instance methods and callback signatures remain supported for backward compatibility.

User functions in the static APIs do not receive SDK context objects:

```java
var result = DurableCoreOperations.step("process", Result.class, () -> {
    var step = StepContext.getCurrentContext();
    return process(step.getAttempt());
});
```

```java
var result = DurableMapOperations.map("process", items, Result.class, item -> {
    var index = MapItemContext.getCurrentContext().getIndex();
    return process(item, index);
});
```

```java
var result = DurableWaitForCallbackOperations.waitForCallback(
        "approval",
        Approval.class,
        () -> submit(WaitForCallbackContext.getCurrentContext().getCallbackId()));
```

```java
var result = DurableWithRetryOperations.withRetry("transaction", () -> {
    var attempt = WithRetryContext.getCurrentContext().getAttempt();
    return executeAttempt(attempt);
});
```

Parallel branch functions are `Supplier<T>`. Wait-for-condition functions receive only the durable state value and
obtain attempt metadata from `StepContext.getCurrentContext()`.

## Current context scopes

`DurableContext.getCurrentContext()` and `ExtensionContext.getCurrentContext()` are available on SDK-managed handler
and child-context threads. `StepContext.getCurrentContext()` is available inside step and wait-for-condition user
functions.

`MapItemContext`, `WaitForCallbackContext`, and `WithRetryContext` are available only inside their corresponding user
function. Nested scopes restore the previous context when they close.

Operation-specific TLS is not automatically propagated into a nested primitive's separate user-function thread. Read
the metadata in its owning function and capture any application value needed by the nested operation:

```java
var result = DurableMapOperations.map("process", items, Result.class, item -> {
    var index = MapItemContext.getCurrentContext().getIndex();
    return DurableCoreOperations.step("process-item", Result.class, () -> process(item, index));
});
```

Current context is not propagated to application-created threads. Durable primitives must be created from an
SDK-managed durable context thread.

## Primitive reservations

Extensions with deterministic call order can use `DurableCoreOperations` directly. Schedulers whose registration
order is deterministic but launch order may vary use `ExtensionContext.reserve(name)`.

Each reservation immediately consumes the next sequential operation ID and returns an opaque, one-shot
`ExtensionOperation`:

```java
var extension = ExtensionContext.getCurrentContext();
var first = extension.reserve("first");
var second = extension.reserve("second");

// Launch order can differ from reservation order.
var secondResult = second.stepAsync(String.class, () -> runSecond());
var firstResult = first.stepAsync(String.class, () -> runFirst());
```

A reservation can create exactly one primitive: step, wait, chained invoke, callback, or child context. Reuse throws
`IllegalStateException`. Raw operation IDs are never exposed.

Create reservations in the same order on every replay. Reordering, inserting, or removing reservations is a workflow
compatibility change because it can associate existing checkpoints with different logical primitives. Launching
already reserved operations in a different order is supported.

## Explicit child contexts

An extension creates a child context only when its own semantics require isolation:

```java
var result = ExtensionContext.getCurrentContext()
        .reserve("isolated-work")
        .runInChildContext(Result.class, () -> executeIsolatedWork());
```

Inside the supplier, `DurableContext.getCurrentContext()` and `ExtensionContext.getCurrentContext()` return the child
context.

## Custom durable futures

An asynchronous extension may return an SDK primitive future or implement `DurableFuture<T>`. Custom composed futures
that participate in `DurableFuture.anyOf` override `completionFuture()`:

```java
private record PairFuture(DurableFuture<String> left, DurableFuture<String> right)
        implements DurableFuture<String> {
    @Override
    public String get() {
        return left.get() + right.get();
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return CompletableFuture.allOf(left.completionFuture(), right.completionFuture());
    }
}
```

Completing or cancelling the returned completion signal must not mutate the underlying durable operations.

## Plugins and failures

Extensions do not create an automatic plugin lifecycle event or checkpoint boundary. Plugins observe the primitive
operations created by the extension. If the extension explicitly creates a child context, plugins also observe that
child-context operation.

Serialization, suspension, replay, cancellation, failures, and checkpointing retain the semantics of the underlying
primitive operations.

## Module compatibility

An extension Maven module should depend only on the public SDK artifact and import public types under
`software.amazon.lambda.durable`. Do not import SDK implementation packages such as `context`, `execution`, or
`operation`.

The supported extension contracts are `ExtensionContext`, `ExtensionOperation`, the static operation facades, the
typed TLS contexts, and `DurableFuture.completionFuture()`.
