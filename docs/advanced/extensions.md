# Custom extension operations

Extension operations are ordinary static Java methods that compose SDK-owned durable primitives. They can live in a
separate Maven module without defining backend operation types, sending checkpoint updates, or depending on SDK
implementation packages.

Extension-author contracts are in `software.amazon.lambda.durable.extension`. Built-in operation APIs are in
`software.amazon.lambda.durable.operation`, while operation-specific TLS metadata contexts remain in
`software.amazon.lambda.durable`.

Application code calls only the extension's API:

```java
import static com.example.durable.PairOperations.pairAsync;

var result = pairAsync("pair", left, right).get();
```

The extension retrieves the active scope internally:

```java
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.extension.ExtensionContext;

public final class PairOperations {
    private PairOperations() {}

    public static DurableFuture<String> pairAsync(
            String name, Supplier<String> leftFunction, Supplier<String> rightFunction) {
        var extension = ExtensionContext.getCurrentContext();
        var left = extension.reserve(name + "-left");
        var right = extension.reserve(name + "-right");
        var config = ExtensionStepConfig.<String>builder().build();

        var leftFuture = left.stepAsync(
                "PairStep",
                TypeToken.get(String.class),
                state -> ExtensionStepResult.succeed(leftFunction.get()),
                config);
        var rightFuture = right.stepAsync(
                "PairStep",
                TypeToken.get(String.class),
                state -> ExtensionStepResult.succeed(rightFunction.get()),
                config);
        return new PairFuture(leftFuture, rightFuture);
    }
}
```

There is no extension registration API and no automatic child-context boundary. The extension chooses whether to
compose primitives in the current scope or explicitly create a child context.

## Static operation APIs

New code can use context-free static operations from `software.amazon.lambda.durable.operation`:

| Facade | Operations |
| --- | --- |
| `DurableStepOperation` | `step`, `stepAsync` |
| `DurableWaitOperation` | `wait`, `waitAsync` |
| `DurableInvokeOperation` | `invoke`, `invokeAsync` |
| `DurableCallbackOperation` | `createCallback` |
| `DurableContextOperation` | `runInChildContext`, `runInChildContextAsync` |
| `DurableMapOperation` | `map`, `mapAsync` |
| `DurableParallelOperation` | `parallel` |
| `DurableWaitForCallbackOperation` | `waitForCallback`, `waitForCallbackAsync` |
| `DurableWaitForConditionOperation` | `waitForCondition`, `waitForConditionAsync` |
| `DurableWithRetryOperation` | `withRetry`, `withRetryAsync` |

The existing `DurableContext` instance methods and callback signatures remain supported for backward compatibility.

Each static operation owns its configuration type. For example:

```java
var config = DurableStepOperation.StepConfig.builder()
        .retryStrategy(RetryStrategies.Presets.DEFAULT)
        .build();
var result = DurableStepOperation.step("process", Result.class, () -> process(), config);
```

The same pattern applies to `DurableInvokeOperation.InvokeConfig`,
`DurableCallbackOperation.CallbackConfig`, `DurableContextOperation.RunInChildContextConfig`,
`DurableMapOperation.MapConfig`, `DurableParallelOperation.ParallelConfig`,
`DurableParallelOperation.ParallelBranchConfig`, `DurableWaitForCallbackOperation.WaitForCallbackConfig`,
`DurableWaitForConditionOperation.WaitForConditionConfig`, and
`DurableWithRetryOperation.WithRetryConfig`.

Map and parallel extend `DurableConcurrencyOperation` and use its shared
`DurableConcurrencyOperation.CompletionConfig` and `DurableConcurrencyOperation.NestingType` configuration types.

The compatibility types in `software.amazon.lambda.durable.config` remain accepted by `DurableContext`. They can be
passed to a static operation through `toOperationConfig()`:

```java
var legacyConfig = software.amazon.lambda.durable.config.StepConfig.builder().build();
DurableStepOperation.step("process", Result.class, () -> process(), legacyConfig.toOperationConfig());
```

## Primitive implementation path

The instance APIs and static operations share the same canonical implementation:

```text
DurableContext.step
  -> DurableStepOperation.step
  -> ExtensionOperation.stepAsync
  -> primitive.StepPrimitive
```

WAIT, CHAINED_INVOKE, CALLBACK, and CONTEXT follow the same path through `DurableWaitOperation`,
`DurableInvokeOperation`, `DurableCallbackOperation`, and `DurableContextOperation`. Each class owns both its
context-free overloads and its `ExtensionContext` implementation; there are no separate built-in extension classes.

`DurableContextImpl` owns the current durable scope and reservations, but it does not construct primitive operation
engines. `extension.ExtensionOperationImpl` is the single internal boundary that creates `StepPrimitive`, `WaitPrimitive`,
`InvokePrimitive`, `CallbackPrimitive`, and `ChildContextPrimitive`.

The extension SPI uses extension-specific configuration types. `ExtensionInvokeConfig` and
`ExtensionCallbackConfig` isolate extension authors and primitive engines from operation-owned configuration; the
corresponding `Durable*Operation` class performs that conversion.

User functions in the static APIs do not receive SDK context objects:

```java
var result = DurableStepOperation.step("process", Result.class, () -> {
    var step = StepContext.getCurrentContext();
    return process(step.getAttempt());
});
```

```java
var result = DurableMapOperation.map("process", items, Result.class, item -> {
    var index = DurableMapOperation.MapItemContext.getCurrentContext().getIndex();
    return process(item, index);
});
```

```java
var result = DurableWaitForCallbackOperation.waitForCallback(
        "approval",
        Approval.class,
        () -> submit(DurableWaitForCallbackOperation.WaitForCallbackContext
                .getCurrentContext()
                .getCallbackId()));
```

```java
var result = DurableWithRetryOperation.withRetry("transaction", () -> {
    var attempt = DurableWithRetryOperation.WithRetryContext.getCurrentContext().getAttempt();
    return executeAttempt(attempt);
});
```

Parallel branch functions are `Supplier<T>`. Wait-for-condition functions receive only the durable state value and
obtain attempt metadata from `StepContext.getCurrentContext()`.

## Current context scopes

`DurableContext.getCurrentContext()` and `ExtensionContext.getCurrentContext()` are available on SDK-managed handler
and child-context threads. `StepContext.getCurrentContext()` is available inside step and wait-for-condition user
functions.

`DurableMapOperation.MapItemContext`, `DurableWaitForCallbackOperation.WaitForCallbackContext`, and
`DurableWithRetryOperation.WithRetryContext` are available only inside their corresponding user function. Nested
scopes restore the previous context when they close.

Operation-specific TLS is not automatically propagated into a nested primitive's separate user-function thread. Read
the metadata in its owning function and capture any application value needed by the nested operation:

```java
var result = DurableMapOperation.map("process", items, Result.class, item -> {
    var index = DurableMapOperation.MapItemContext.getCurrentContext().getIndex();
    return DurableStepOperation.step("process-item", Result.class, () -> process(item, index));
});
```

Current context is not propagated to application-created threads. Durable primitives must be created from an
SDK-managed durable context thread.

## Primitive reservations

Extensions with deterministic call order can use the matching built-in operation directly. Schedulers whose
registration order is deterministic but launch order may vary use `ExtensionContext.reserve(name)`.

Each reservation immediately consumes the next sequential operation ID and returns an opaque, one-shot
`ExtensionOperation`:

```java
var extension = ExtensionContext.getCurrentContext();
var first = extension.reserve("first");
var second = extension.reserve("second");
var config = ExtensionStepConfig.<String>builder().build();

// Launch order can differ from reservation order.
var secondResult = second.stepAsync(
        "ScheduledStep",
        TypeToken.get(String.class),
        state -> ExtensionStepResult.succeed(runSecond()),
        config);
var firstResult = first.stepAsync(
        "ScheduledStep",
        TypeToken.get(String.class),
        state -> ExtensionStepResult.succeed(runFirst()),
        config);
```

A reservation can create exactly one primitive: step, wait, chained invoke, callback, or child context. Reuse throws
`IllegalStateException`. Raw operation IDs are never exposed.

`ExtensionOperation` exposes one fully specified asynchronous method for each primitive. Callers always provide the
subtype, use `TypeToken<T>` for typed results, and supply the complete primitive configuration. Extensions can call
`get()` when they need blocking behavior or use the matching built-in operation when reservation-time ID allocation is
not needed.

Create reservations in the same order on every replay. Reordering, inserting, or removing reservations is a workflow
compatibility change because it can associate existing checkpoints with different logical primitives. Launching
already reserved operations in a different order is supported.

### Custom local IDs

Schedulers whose registration order can change may reserve an explicit local ID:

```java
var node = ExtensionContext.getCurrentContext().reserve("process-node", "node-a");
var result = node.stepAsync(
        "ProcessNode",
        TypeToken.get(NodeResult.class),
        state -> ExtensionStepResult.succeed(processNode("node-a")),
        ExtensionStepConfig.<NodeResult>builder().build());
```

The local ID must be non-null, nonblank, and unique within the current context. It is never used directly as the
backend operation ID. The SDK namespaces and hashes it as follows:

```text
root context:  sha256(localOperationId)
child context: sha256(parentContextId + "-" + localOperationId)
```

Custom reservations and generated sequential operations share one local-ID registry. A custom reservation advances
the sequence once, generated numeric IDs skip values that were already claimed, and collisions fail immediately.
Changing or reusing a local ID is a workflow compatibility change.

### Custom primitive subtypes

Subtype-aware reservation overloads record an extension-specific identity while retaining the selected primitive's
SDK-owned state machine:

```java
var result = ExtensionContext.getCurrentContext()
        .reserve("process-node", "node-a")
        .stepAsync(
                "AcmeNode",
                TypeToken.get(NodeResult.class),
                state -> ExtensionStepResult.succeed(processNode("node-a")),
                ExtensionStepConfig.<NodeResult>builder().build());
```

The selector still determines the backend operation type: `stepAsync` creates a `STEP`, `waitAsync` creates a `WAIT`,
and so on. The subtype must be non-null and nonblank. It appears in checkpoints, replay validation, plugins, logs,
and failure metadata, so changing it is a workflow compatibility change.

## Stateful extension steps

A stateful extension STEP can checkpoint application state between attempts without exposing raw checkpoint actions:

```java
var result = ExtensionContext.getCurrentContext()
        .reserve("poll")
        .stepAsync(
                "AcmePoll",
                TypeToken.get(PollState.class),
                state -> state.complete()
                        ? ExtensionStepResult.succeed(state)
                        : ExtensionStepResult.retry(refresh(state), Duration.ofSeconds(5)),
                ExtensionStepConfig.<PollState>builder()
                        .initialState(initialState)
                        .build());
```

The function may return only `ExtensionStepResult.succeed(value)` or
`ExtensionStepResult.retry(state, delay)`. Retry state uses the configured `SerDes`; attempt metadata remains
available through `StepContext.getCurrentContext()`. Thrown exceptions follow the normal STEP failure path.
`ExtensionStepConfig` owns its retry strategy, which returns the same retry outcome used by stateful continuations.
Extension libraries can therefore configure exception retries and delivery semantics without depending on the
customer-facing config or retry packages:

```java
ExtensionStepConfig.<PollState>builder()
        .retryStrategy((error, state, attempt) -> attempt < 3
                ? ExtensionStepResult.retry(state, Duration.ofSeconds(1))
                : ExtensionStepResult.doNotRetry())
        .semanticsPerRetry(ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY)
        .build();
```

## Configurable extension contexts

An advanced CONTEXT primitive separates the application result from optional replay state:

```java
var result = ExtensionContext.getCurrentContext()
        .reserve("batch")
        .runInChildContextAsync(
                "AcmeBatch",
                TypeToken.get(BatchResult.class),
                () -> {
                    var replay = ExtensionContextReplayContext.<BatchResult>getCurrentContext();
                    var previous = replay.isReplayingChildren() ? replay.getReplayState() : null;
                    var current = rebuildBatch(previous);
                    return ExtensionContextResult.replayChildren(current, compact(current));
                },
                ExtensionContextConfig.builder()
                        .emitUserFunctionEvents(false)
                        .suppressLateChildCheckpoints(true)
                        .errorHandler(failure -> new IllegalStateException(
                                "Batch context failed: " + failure.contextName()))
                        .build());
```

Use `ExtensionContextResult.completed(result)` when children never need to replay,
`replayChildren(result, replayState)` to always replay them, or
`replayChildrenAboveSize(result, replayState, thresholdBytes)` to replay only when the serialized full result reaches
the threshold. Replay metadata is scoped to the framework callback through `ExtensionContextReplayContext`.

`ExtensionContextConfig` directly configures the context serializer and whether the context is virtual. It also
controls framework user-function plugin events and can suppress child checkpoints that finish after the parent. If a
context fails, the SDK first rethrows a deserialized original exception, then calls the configured error handler, and
finally falls back to `ChildContextFailedException`. The handler receives read-only context metadata and
child-operation summaries.

## Explicit child contexts

An extension creates a child context only when its own semantics require isolation:

```java
var result = ExtensionContext.getCurrentContext()
        .reserve("isolated-work")
        .runInChildContextAsync(
                "IsolatedWork",
                TypeToken.get(Result.class),
                () -> ExtensionContextResult.completed(executeIsolatedWork()),
                ExtensionContextConfig.builder().build())
        .get();
```

Inside the function, `DurableContext.getCurrentContext()` and `ExtensionContext.getCurrentContext()` return the child
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

The built-in map, parallel, wait-for-callback, wait-for-condition, and with-retry families are implemented through
the same extension primitives. Their legacy `DurableContext` methods and context-free static APIs share one
canonical implementation while preserving their established checkpoint topology and plugin behavior.

## Module compatibility

An extension Maven module should depend only on the public SDK artifact and import public types under
`software.amazon.lambda.durable`, `software.amazon.lambda.durable.operation`, and
`software.amazon.lambda.durable.extension`. Do not import SDK implementation packages such as `context`, `execution`,
or `primitive`.

The extension-author SPI includes `ExtensionContext`, `ExtensionOperation`, stateful-step contracts, and configurable
extension-context contracts under `software.amazon.lambda.durable.extension`. Static operation APIs are under
`software.amazon.lambda.durable.operation`; typed TLS contexts and `DurableFuture.completionFuture()` remain under
`software.amazon.lambda.durable`.
