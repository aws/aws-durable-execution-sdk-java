# Migrate Built-In Operations to the Extension API

## Objective

Rewrite the SDK's existing non-primitive operations as built-in extensions using the public extension operation
model. Preserve every existing user-facing interface, configuration type, overload, result, exception, checkpoint
shape, replay behavior, plugin event, and concurrency behavior.

The migrated operation families are:

- map
- parallel
- wait for callback
- wait for condition
- with retry

The existing `DurableContext` methods remain supported compatibility APIs. They delegate to the same implementations
used by the static built-in extension facades.

## Compatibility Boundary

The following existing public APIs remain unchanged:

- `DurableContext`
- `ParallelDurableFuture`
- `MapConfig`
- `ParallelConfig`
- `WaitForCallbackConfig`
- `WaitForConditionConfig`
- `WithRetryConfig`
- `StepConfig`
- `RunInChildContextConfig`
- all existing result and exception types

This migration may add public extension-specific interfaces, overloads, and configuration types. It must not add
methods to the existing operation interfaces or fields to their existing configuration types.

The following observable behavior remains unchanged:

- operation IDs and parent-child ID namespaces
- operation types, subtype strings, names, and tree shape
- checkpoint action sequences and replay validation
- serialized result and failure payloads
- map and parallel completion decisions
- concurrency limits and skipped item behavior
- nested and flat concurrency modes
- large-result replay-children behavior
- wait-for-condition state, attempts, and delays
- wait-for-callback failure and timeout translation
- retry backoff names and virtual-context behavior
- plugin operation and user-function event ordering

## Primitive State Machines

Extension authors may select operation subtype strings, but they may not define checkpoint state machines.

Each primitive retains its SDK-owned lifecycle:

| Primitive selector | Backend operation type | SDK-owned state machine |
| --- | --- | --- |
| step | `STEP` | start, retry, ready, succeed, fail |
| wait | `WAIT` | start, poll, succeed |
| invoke | `CHAINED_INVOKE` | start, poll, succeed, fail |
| callback | `CALLBACK` | start, poll, succeed, fail, timeout |
| child context | `CONTEXT` | start, execute/replay children, succeed, fail |

The primitive selector determines the backend operation type. The supplied subtype is metadata used for checkpoint
validation, plugins, exception translation, and execution history.

Subtype strings must be non-null and nonblank. The SDK does not restrict them to an allow-list because the backend
accepts arbitrary subtype strings.

## Subtype-Aware Reservations

`ExtensionOperation` gains subtype-aware overloads for every primitive. Existing overloads remain and use the current
standard subtype strings.

Representative asynchronous signatures:

```java
<T> DurableFuture<T> stepAsync(
        String subType,
        TypeToken<T> resultType,
        Supplier<T> function,
        StepConfig config);

DurableFuture<Void> waitAsync(
        String subType,
        Duration duration);

<T, U> DurableFuture<T> invokeAsync(
        String subType,
        String functionName,
        U payload,
        TypeToken<T> resultType,
        InvokeConfig config);

<T> DurableCallbackFuture<T> createCallback(
        String subType,
        TypeToken<T> resultType,
        CallbackConfig config);

<T> DurableFuture<T> runInChildContextAsync(
        String subType,
        TypeToken<T> resultType,
        Supplier<T> function,
        RunInChildContextConfig config);
```

The existing no-subtype methods delegate with these values:

- `Step`
- `Wait`
- `ChainedInvoke`
- `Callback`
- `RunInChildContext`

Synchronous, `Class<T>`, and default-configuration overloads remain default methods.

## Custom Local Operation IDs

`ExtensionContext` retains sequential reservation and adds a custom-local-ID overload:

```java
ExtensionOperation reserve(String name);

ExtensionOperation reserve(String name, String localOperationId);
```

The custom value replaces the generated sequence number for that reservation. It is not the final backend operation
ID.

Custom local IDs must be non-null and nonblank. They are otherwise treated as opaque UTF-8 strings.

The SDK constructs the final ID using the current context namespace:

```text
root context:  sha256(localOperationId)
child context: sha256(parentContextId + "-" + localOperationId)
```

Every reservation occupies one position in the context's reservation sequence:

1. A sequential reservation advances the counter until it finds an unused numeric local ID.
2. A custom reservation validates uniqueness, advances the counter once, and uses the supplied local ID.
3. A generated numeric local ID skips values already claimed by custom reservations.
4. Reusing a local ID in the same context fails immediately.

Primitive operations created without a reservation use the same counter and local-ID registry. A custom reservation
therefore cannot reuse a numeric local ID already consumed by an ordinary core operation.

Examples:

```text
reserve("a", "node-a") -> hash("node-a")
reserve("b")           -> hash("2")
reserve("c", "2")      -> fails because "2" is already used
```

Inside a child context whose ID is `parentHash`:

```text
reserve("a", "node-a") -> hash("parentHash-node-a")
```

Custom local IDs allow the custom-ID operations themselves to keep stable identities when definition order changes.
Sequential operations around them may still receive different IDs. Adding, removing, or changing an ID remains a
workflow compatibility change.

## Stateful Step Extensions

`waitForCondition` needs the existing STEP state machine with checkpointed state between retry attempts. The extension
API exposes this without exposing raw checkpoint actions.

```java
@FunctionalInterface
public interface ExtensionStepFunction<T> {
    ExtensionStepResult<T> apply(T state);
}
```

```java
public sealed interface ExtensionStepResult<T> {
    static <T> ExtensionStepResult<T> succeed(T value);

    static <T> ExtensionStepResult<T> retry(T state, Duration delay);
}
```

```java
public final class ExtensionStepConfig<T> {
    T initialState();

    SerDes serDes();
}
```

The subtype-aware stateful step selector is:

```java
<T> DurableFuture<T> stepAsync(
        String subType,
        TypeToken<T> resultType,
        ExtensionStepFunction<T> function,
        ExtensionStepConfig<T> config);
```

The SDK interprets results through the fixed STEP state machine:

- `succeed(value)` serializes the value and checkpoints `SUCCEED`.
- `retry(state, delay)` serializes the state and checkpoints `RETRY`.
- a thrown exception checkpoints `FAIL`.
- internal suspension and unrecoverable control-flow exceptions propagate without conversion.

`StepContext.getCurrentContext()` exposes the one-based attempt number. The extension function receives only its
application state.

## Extension Context Results and Replay State

A subtype-aware extension context may return a full application result and a smaller replay state:

```java
public final class ExtensionContextResult<T> {
    static <T> ExtensionContextResult<T> completed(T result);

    static <T> ExtensionContextResult<T> replayChildren(
            T result,
            T replayState);

    static <T> ExtensionContextResult<T> replayChildrenAboveSize(
            T result,
            T replayState,
            int thresholdBytes);
}
```

The application receives `result`. The checkpoint stores either the normal serialized result or `replayState`,
according to the selected factory.

For `replayChildrenAboveSize`, the threshold is evaluated against the serialized full application result, before the
replay state is selected for checkpointing.

When replay-children is enabled, the SDK reexecutes the extension context function and exposes the stored replay state
through a scoped extension context:

```java
public final class ExtensionContextReplayContext<T> {
    static <T> ExtensionContextReplayContext<T> getCurrentContext();

    boolean isReplayingChildren();

    T getReplayState();
}
```

The replay context is available only while the extension framework function is running. It is restored across nested
extension contexts and is not propagated to application-created threads.

This preserves current map and parallel behavior:

- A small map stores and replays its complete `MapResult`.
- A large map stores statuses and completion reason as replay state, then reconstructs values from iteration
  checkpoints.
- Parallel stores its current `ParallelResult` as replay state and always replays branch children.

## Extension Context Failure Translation

The CONTEXT state machine always handles failures in the same way:

1. serialize the thrown exception when possible
2. checkpoint `FAIL`
3. deserialize the original exception when the future is read or replayed

Subtype-specific behavior is customizable only as a fallback when the original exception cannot be reconstructed.

```java
@FunctionalInterface
public interface ExtensionContextErrorHandler {
    Throwable translate(ExtensionContextFailure failure);
}
```

`ExtensionContextFailure` is a read-only view containing:

- context name
- context subtype string
- deserialized original exception, when available
- serialized error metadata
- child operation summaries

Each child summary contains:

- operation type
- subtype string
- status
- serialized error metadata

Resolution order is:

1. rethrow a deserialized original exception
2. invoke the configured `ExtensionContextErrorHandler`
3. fall back to `ChildContextFailedException`

The built-in extensions provide handlers that preserve current behavior:

- wait for callback distinguishes callback failure, callback timeout, and submitter failure
- map iteration falls back to `MapIterationFailedException`
- parallel branch falls back to `ParallelBranchFailedException`
- with retry and ordinary child contexts fall back to `ChildContextFailedException`

## Extension Context Configuration

Existing `RunInChildContextConfig` remains unchanged and continues to provide SerDes and virtual-context settings.

The subtype-aware extension context overload uses a new extension-specific wrapper:

```java
public final class ExtensionContextConfig {
    RunInChildContextConfig childContextConfig();

    ExtensionContextErrorHandler errorHandler();

    boolean emitUserFunctionEvents();

    boolean suppressLateChildCheckpoints();
}
```

The extension context function has a distinct type so it does not conflict by erasure with existing supplier
overloads:

```java
@FunctionalInterface
public interface ExtensionContextFunction<T> {
    ExtensionContextResult<T> apply();
}
```

```java
<T> DurableFuture<T> runInChildContextAsync(
        String subType,
        TypeToken<T> resultType,
        ExtensionContextFunction<T> function,
        ExtensionContextConfig config);
```

The two additional booleans preserve existing family-specific behavior:

- `emitUserFunctionEvents` controls whether the extension context function is reported as a user function. It defaults
  to `true`, matching ordinary child contexts.
- `suppressLateChildCheckpoints` tracks extension-managed children and prevents them from writing checkpoints after
  their parent extension context has completed. It defaults to `false`.

Nested user step and child-context functions retain their own existing plugin hooks regardless of the parent setting.

## Internal Operation Identity

The existing public `OperationSubType` enum and enum-based identity factories remain unchanged.

Internally, primitive operations use an identity containing:

- operation ID
- name
- backend operation type
- subtype string

Existing enum values convert to this representation. Custom subtype strings flow unchanged through:

- operation updates
- replay validation
- plugin events
- logs
- failure views

Replay validation compares both operation type and exact subtype string.

## Built-In Extension Implementations

Each family has one canonical implementation that accepts an `ExtensionContext`. The static facade obtains it from
TLS. The corresponding `DurableContextImpl` method passes `this`.

```text
static facade --------------------+
                                  +-> built-in extension -> extension primitives
legacy DurableContext adapter ----+
```

The canonical implementations use the existing public configurations and callback contracts after adapting them to
the extension-specific functions.

### Wait for callback

The extension:

1. reserves a CONTEXT operation with subtype `WaitForCallback`
2. creates a CALLBACK child with subtype `Callback`
3. creates a STEP child with subtype `Step`
4. runs the submitter
5. waits for the callback result

The parent extension context emits the same context user-function hooks as the current implementation.
The context failure handler preserves callback failure, timeout, and submitter exception translation.

### With retry

The extension:

1. reserves a CONTEXT operation with subtype `WithRetry`
2. uses the current virtual or checkpointed behavior from `WithRetryConfig`
3. invokes the user operation with attempt metadata in `WithRetryContext` TLS
4. creates WAIT operations for backoff using the existing names and delays

The retry context emits the same context user-function hooks as the current implementation.
Internal suspension and unrecoverable control-flow exceptions are never retried.

### Wait for condition

The extension reserves a stateful STEP operation with subtype `WaitForCondition`.

The adapter:

1. starts with `WaitForConditionConfig.initialState()`
2. invokes the existing check function
3. returns `succeed(value)` when polling completes
4. evaluates the existing wait strategy
5. returns `retry(value, delay)` when polling continues

Attempt metadata remains available through `StepContext`.

### Map

The extension reserves a CONTEXT operation with subtype `Map`.

Inside that context it:

1. deterministically reserves all iteration contexts in input order
2. assigns subtype `MapIteration`
3. launches iterations through the shared concurrency coordinator
4. evaluates the existing `CompletionConfig`
5. constructs the existing `MapResult`
6. uses map replay state for large results

Iteration reservations continue using sequential local IDs so existing operation IDs remain unchanged.

The map parent does not emit context user-function hooks. Iteration contexts do emit them. The parent enables
late-child checkpoint suppression.

Empty maps preserve `DurableConfig.shouldCheckpointEmptyMap()`:

- when enabled, the map parent checkpoints `START` and `SUCCEED`
- when disabled, the reservation still consumes the same operation ID, the map emits the existing warning and plugin
  lifecycle, and it completes with `MapResult.empty()` without a backend checkpoint

### Parallel

The extension reserves a CONTEXT operation with subtype `Parallel` and returns the existing
`ParallelDurableFuture`.

Branch calls:

1. reserve branch identities in registration order
2. assign subtype `ParallelBranch`
3. enqueue branch definitions in the parent extension context
4. launch through the shared concurrency coordinator

`close()` and `get()` retain current join behavior. Branch registration after join still fails.

Each branch call returns a deferred `DurableFuture` immediately. The coordinator binds it to the reserved child
context future when concurrency capacity permits. Its `get()` and `completionFuture()` retain the behavior of the
current branch future.

The parallel parent does not emit context user-function hooks. Branch contexts do emit them. The parent always stores
replay state, replays children, and enables late-child checkpoint suppression.

## Shared Concurrency Coordinator

Map and parallel share a coordinator that is not itself a durable operation.

It owns:

- pending registration order
- max-concurrency enforcement
- running completion signals
- success and failure counts
- `CompletionConfig` evaluation
- skipped item tracking
- late-child checkpoint suppression
- the `allItemsRegistered` transition when map registration completes or parallel is joined

It creates no operation type or checkpoint. All durable state belongs to the parent extension context and its reserved
child context primitives.

The coordinator uses only `DurableFuture.completionFuture()` and reserved extension operations. It does not downcast
futures to SDK operation classes.

## Removal of Specialized Engines

After parity is proven, the following specialized engines are removed:

- `MapOperation`
- `ParallelOperation`
- `ConcurrencyOperation`
- `WaitForConditionOperation`

Their reusable primitive lifecycle behavior moves into the generalized STEP and CONTEXT primitive implementations.

`ChildContextOperation`, `StepOperation`, and the other primitive operation classes remain as the SDK-owned state
machines. They are generalized to accept string subtypes and extension-specific result or failure policies.

## Testing Strategy

### Reservation tests

Cover:

- sequential reservations retain current IDs
- custom local IDs use the current context namespace
- custom reservations advance the sequence position
- generated numeric IDs skip reserved custom values
- duplicate local IDs fail
- nested contexts hash custom IDs with their parent ID
- custom IDs remain stable when reservation order changes

### Primitive extension tests

Cover:

- arbitrary subtype strings for every primitive
- operation type remains determined by the primitive selector
- replay rejects type or subtype changes
- subtype strings reach plugin events unchanged
- stateful step success, retry, replay, state serialization, and failure
- context replay state and nested TLS restoration
- custom context failure translation and default fallback

### Built-in parity tests

For every operation family, compare legacy and static entry points for:

- results and thrown exception types
- operation IDs, names, types, subtypes, and parent IDs
- checkpoint status and payload shape
- replay and suspension behavior
- plugin lifecycle ordering

The existing map, parallel, callback, condition, retry, plugin, conformance, and example tests remain behavioral gates.
Tests formerly tied to specialized classes move to the generalized primitive and coordinator implementations without
weakening their assertions.

### Completion gate

Run:

```bash
mvn spotless:apply
mvn clean install
```

Cloud example tests remain disabled unless their existing environment requirements are configured.

## Documentation and ADR

Update:

- ADR-006 to include custom local IDs, arbitrary subtype strings, fixed primitive state machines, replay state, and
  customizable context failure translation
- the custom extension guide with subtype-aware and custom-ID examples
- public Javadocs for every new extension-specific contract

The documentation must state that operation IDs, subtypes, and replay state are workflow compatibility contracts.
