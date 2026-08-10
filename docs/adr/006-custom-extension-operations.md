# ADR-006: Public API for Custom Extension Operations

**Status:** Accepted

**Date:** 2026-08-10

## Context

Issue [#571](https://github.com/aws/aws-durable-execution-sdk-java/issues/571) requests a supported way to implement
reusable durable operations in separate Maven modules without changing or rebuilding the core SDK.

The SDK historically exposed all operations as instance methods on `DurableContext`. This created several
constraints:

- Adding an optional operation required changing `DurableContext` and `DurableContextImpl`.
- Extension libraries could not reserve stable operation identities before execution order was known.
- Extension code needed implementation classes to reproduce subtype-specific checkpoint and replay behavior.
- User-function APIs received SDK-created contexts and metadata as callback parameters.
- Built-in composed operations were split between static facades and dedicated operation engines instead of proving
  the same extension model available to third parties.

Extension operations do not share one execution scope. Some operate directly in the current context, while others
create child contexts. The extension mechanism must not impose a universal child-context boundary.

The backend accepts arbitrary operation subtype strings, but each backend operation type has a fixed state machine.
Extensions need subtype control, replay state, and failure translation without receiving raw checkpoint actions or
defining new backend state machines.

## Decision

### Preserve Existing Customer Operation APIs

Keep every existing method signature, callback contract, configuration field, result type, exception, and behavior
unchanged. New capabilities are additive and limited to extension-specific overloads and types.

This includes:

- `DurableContext`
- `ParallelDurableFuture`
- `MapConfig`
- `ParallelConfig`
- `WaitForCallbackConfig`
- `WaitForConditionConfig`
- `WithRetryConfig`
- `StepConfig`
- `RunInChildContextConfig`

`ExtensionContext` and `ExtensionOperation` are new extension-author SPI contracts. `ExtensionOperation` deliberately
exposes only one fully specified asynchronous method per primitive instead of mirroring the customer-facing overload
families. Extension libraries can add their own conveniences, and deterministic operations can use the matching
built-in operation directly.

The existing `DurableContext` methods remain compatibility APIs. Their implementations delegate to the same built-in
operation classes used by the static APIs.

Each built-in operation owns its public nested configuration type, such as
`DurableStepOperation.StepConfig` or `DurableMapOperation.MapConfig`. The compatibility types under
`software.amazon.lambda.durable.config` convert through `toOperationConfig()` at the `DurableContext` boundary.

### Expose Primitive and Built-In Extension Facades

Expose each primitive through an independently maintained class:

| Facade | Primitive |
| --- | --- |
| `DurableStepOperation` | STEP |
| `DurableWaitOperation` | WAIT |
| `DurableInvokeOperation` | CHAINED_INVOKE |
| `DurableCallbackOperation` | CALLBACK |
| `DurableContextOperation` | CONTEXT |

The customer-facing primitive APIs use the extension SPI internally. For example:

```text
DurableContext.step
  -> DurableStepOperation.step
  -> ExtensionOperation.stepAsync
  -> primitive.StepPrimitive
```

The other primitives follow the same dependency direction through their matching merged operation class.
`DurableContextImpl` provides the durable scope and reservation mechanism. Only
`extension.ExtensionOperationImpl` constructs
the concrete primitive operation engines, so customer APIs and third-party extensions share one backend boundary.

Expose each built-in extension family through an independently maintained class:

| Facade | Operation family |
| --- | --- |
| `DurableMapOperation` | `map`, `mapAsync` |
| `DurableParallelOperation` | `parallel` and branch construction |
| `DurableWaitForCallbackOperation` | `waitForCallback`, `waitForCallbackAsync` |
| `DurableWaitForConditionOperation` | `waitForCondition`, `waitForConditionAsync` |
| `DurableWithRetryOperation` | `withRetry`, `withRetryAsync` |

An extension is an ordinary static Java method. There is no registration API and no universal
`DurableExtensions.run` boundary.

### Separate Extension-Author Contracts

Place contracts intended specifically for extension authors in:

```text
software.amazon.lambda.durable.extension
```

This package contains `ExtensionContext`, `ExtensionOperation`, stateful-step contracts, and configurable
extension-context contracts.

Place merged built-in operation APIs in:

```text
software.amazon.lambda.durable.operation
```

Each `Durable*Operation` class owns its context-free overloads and its canonical `ExtensionContext` implementation.
There are no separate built-in `*Extension` classes.

Keep established SDK types such as `DurableFuture`, `StepContext`, and `TypeToken` in the root
`software.amazon.lambda.durable` package. Keep operation-specific TLS metadata nested under its owning operation:

- `DurableMapOperation.MapItemContext`
- `DurableWaitForCallbackOperation.WaitForCallbackContext`
- `DurableWithRetryOperation.WithRetryContext`

Backend primitive engines remain internal under `software.amazon.lambda.durable.primitive`.

### Use Scoped Current Context

SDK-managed handler and child contexts implement `ExtensionContext`. Step contexts do not.

User functions in the new APIs receive only application-provided values. SDK-created contexts and metadata are
retrieved from scoped thread-local contexts:

- `DurableContext`
- `ExtensionContext`
- `StepContext`
- `DurableMapOperation.MapItemContext`
- `DurableWaitForCallbackOperation.WaitForCallbackContext`
- `DurableWithRetryOperation.WithRetryContext`
- extension replay contexts

Nested scopes restore the preceding value. Current context is available only on SDK-managed threads and is not
propagated to application-created threads.

### Reserve Sequential or Custom Local Identities

`ExtensionContext` supports sequential and custom-local-ID reservations:

```java
ExtensionOperation reserve(String name);

ExtensionOperation reserve(String name, String localOperationId);
```

Both forms return opaque, one-shot `ExtensionOperation` handles.

Sequential reservations use the next available numeric local ID. A custom reservation replaces the sequence number
for that position with a non-null, nonblank caller-provided local ID.

The SDK constructs the final backend ID:

```text
root context:  sha256(localOperationId)
child context: sha256(parentContextId + "-" + localOperationId)
```

Every operation allocation in a context shares one counter and local-ID registry. Custom reservations advance the
counter once, generated numeric IDs skip already claimed values, and duplicate local IDs fail immediately.

Extension authors never provide or observe the final globally stored operation ID.

### Allow Arbitrary Subtype Strings

`ExtensionOperation` provides one subtype-aware method for every primitive:

```java
<T> DurableFuture<T> stepAsync(
        String subType,
        TypeToken<T> resultType,
        ExtensionStepFunction<T> function,
        ExtensionStepConfig<T> config);

DurableFuture<Void> waitAsync(String subType, Duration duration);

<T, U> DurableFuture<T> invokeAsync(
        String subType,
        String functionName,
        U payload,
        TypeToken<T> resultType,
        ExtensionInvokeConfig config);

<T> DurableCallbackFuture<T> createCallback(
        String subType,
        TypeToken<T> resultType,
        ExtensionCallbackConfig config);

<T> DurableFuture<T> runInChildContextAsync(
        String subType,
        TypeToken<T> resultType,
        ExtensionContextFunction<T> function,
        ExtensionContextConfig config);
```

The primitive selector determines the backend operation type. The string controls only the subtype recorded in
checkpoints, replay validation, plugins, logs, and error metadata.

Subtype strings must be non-null and nonblank. They are not restricted to the existing `OperationSubType` enum.
Extensions use the corresponding `OperationSubType` value when they want a standard subtype.

### Keep Primitive State Machines Fixed

Extension authors cannot send raw checkpoint updates or define arbitrary state machines.

Each primitive retains its SDK-owned lifecycle:

| Primitive | Backend operation type | Lifecycle |
| --- | --- | --- |
| step | `STEP` | start, retry, ready, succeed, fail |
| wait | `WAIT` | start, poll, succeed |
| invoke | `CHAINED_INVOKE` | start, poll, succeed, fail |
| callback | `CALLBACK` | start, poll, succeed, fail, timeout |
| child context | `CONTEXT` | start, execute or replay children, succeed, fail |

A stateful extension STEP may return only:

- `ExtensionStepResult.succeed(value)`
- `ExtensionStepResult.retry(state, delay)`

The SDK maps those outcomes onto the fixed STEP lifecycle. Thrown exceptions follow the normal STEP failure path.
Attempt metadata remains available through `StepContext`.

### Support Context Replay State

A subtype-aware extension context returns `ExtensionContextResult<T>`, which separates the application result from
optional replay state.

Supported result policies are:

- completed with the normal result
- always replay children and store a replay state
- replay children above a serialized-size threshold and store a replay state

On replay, the framework function receives the stored replay state through scoped TLS. This supports large map
results and parallel branch reconstruction without exposing checkpoint APIs.

`ExtensionContextConfig` directly owns the child context serializer and virtual-context flag, plus extension-only
behavior:

- context failure translation
- whether the framework function emits user-function plugin events
- whether late child checkpoints are suppressed after parent completion

Existing configuration classes are not changed.

### Allow Context Failure Translation

The CONTEXT state machine always serializes failures and checkpoints `FAIL`. Exception translation is customizable
when the original exception cannot be reconstructed.

An `ExtensionContextErrorHandler` receives a read-only `ExtensionContextFailure` containing:

- context name and subtype
- error metadata
- child operation type, subtype, status, and error summaries

Resolution order is:

1. rethrow a deserialized original exception
2. invoke the configured error handler
3. fall back to `ChildContextFailedException`

This preserves the existing fallback behavior for callback failures and timeouts, map iterations, parallel branches,
with-retry contexts, and ordinary child contexts.

### Support Composed Durable Futures

`DurableFuture.completionFuture()` provides a public, non-mutating completion signal. Custom composed futures override
it when they support `DurableFuture.anyOf`.

Map and parallel use a shared concurrency coordinator built from reserved extension context operations and public
completion signals. The coordinator is not a durable operation and creates no checkpoint of its own.

### Implement Built-Ins Through Extensions

Rewrite the existing composed operation families using the extension contract:

- wait for callback uses a `WaitForCallback` context containing callback and step primitives
- with retry uses a virtual or checkpointed `WithRetry` context plus wait primitives
- wait for condition uses a stateful `WaitForCondition` step
- map uses a `Map` context and reserved `MapIteration` contexts
- parallel uses a `Parallel` context and dynamically registered `ParallelBranch` contexts

The legacy `DurableContext` methods and static APIs adapt into the same canonical family implementations.

After behavior and checkpoint parity are proven, remove the specialized:

- `MapOperation`
- `ParallelOperation`
- `ConcurrencyOperation`
- `WaitForConditionOperation`

Primitive operation classes remain and are generalized for string subtypes, replay state, and extension failure
policies.

### Preserve Checkpoint and Plugin Compatibility

The migration preserves:

- sequential operation IDs and parent-child namespaces
- existing type, subtype, name, and operation tree shape
- checkpoint actions, payloads, statuses, and replay validation
- map and parallel completion, skipped items, nesting, and large-result behavior
- wait-for-condition state, attempts, and delays
- wait-for-callback exception translation
- retry naming and virtual-context behavior
- plugin operation and user-function event ordering
- non-checkpointed empty-map behavior

Extension framework callbacks emit user-function events only when configured. Nested application callbacks retain
their existing plugin events.

## Alternatives Considered

### Keep Dedicated Engines Behind Static Facades

Retain `MapOperation`, `ParallelOperation`, `ConcurrencyOperation`, and `WaitForConditionOperation`, while making only
the public facades look like extensions.

**Rejected because:**

- Built-ins would not validate that the public extension model is sufficient.
- Static and legacy APIs would continue to depend on a separate implementation architecture.
- Extension authors could not reproduce the capabilities exercised by built-ins.

### Allow Arbitrary Checkpoint State Machines

Expose raw `START`, `RETRY`, `SUCCEED`, `FAIL`, polling, and operation-update APIs.

**Rejected because:**

- Extensions could violate backend transition rules.
- Suspension, replay, and error handling would become extension-author responsibilities.
- The SDK would no longer own checkpoint correctness.

### Restrict Subtypes to OperationSubType

Allow extension operations to use only the SDK's existing enum values.

**Rejected because:**

- The backend accepts arbitrary subtype strings.
- Third-party extensions need distinct history and plugin identities.
- Adding an extension subtype would otherwise require a core SDK release.

### Accept Exact Global Operation IDs

Allow callers to provide the final backend operation ID.

**Rejected because:**

- Callers would need to understand context namespaces and hashing.
- Nested extensions could collide with unrelated operations.
- Backend identity details would become public workflow contracts.

Custom IDs are therefore local values that the SDK namespaces and hashes.

### Derive IDs from Operation Names

Use operation names as local IDs automatically.

**Rejected because:**

- Names are not required to be unique.
- Changing a display name would silently change checkpoint identity.
- Explicit local IDs make the compatibility decision visible.

### Require Every Extension to Run in a Child Context

Provide a universal extension runner that always creates a child context.

**Rejected because:**

- Direct primitive wrappers do not need a child context.
- It imposes checkpoint and replay behavior unrelated to the extension's semantics.
- Each extension must select its own scope.

### Add Extension Families to DurableContext

Continue adding new built-in or third-party operation methods to `DurableContext`.

**Rejected because:**

- It expands the legacy interface for optional features.
- It prevents independently maintained extension modules.
- It retains context-bearing callback signatures.

### Pass SDK Contexts and Metadata as Callback Arguments

Mirror the existing `DurableContext` callback signatures in the new APIs.

**Rejected because:**

- New APIs consistently use scoped current contexts.
- Generated values such as indexes, attempts, and callback IDs belong to typed metadata contexts.
- Context-free callbacks compose without threading SDK objects through application code.

### Use One Built-In Extension Facade

Place every built-in composed operation in one class.

**Rejected because:**

- Unrelated overloads, tests, and documentation would change together.
- Map and parallel require independently maintainable APIs.
- One class would become a second monolithic operation interface.

## Consequences

**Positive:**

- Third-party Maven modules can implement durable operations using supported public contracts.
- Custom subtype strings do not require SDK enum changes.
- Custom local IDs support replay-stable schedulers without exposing global IDs.
- `DurableContext` and existing operation configurations remain compatible.
- Built-in operations prove the same extension architecture available to third parties.
- The SDK continues to own all backend state machines and checkpoint transitions.
- Replay state and failure translation support advanced context extensions without raw checkpoint access.
- Static and legacy APIs share one implementation per operation family.

**Negative:**

- Primitive operation implementations become more general and carry extension policies.
- Custom IDs require per-context collision tracking shared by reserved and direct operations.
- Extension authors must treat subtype strings, local IDs, and replay state as workflow compatibility contracts.
- Extension context failure handlers must remain deterministic and side-effect free.
- Map and parallel require a reusable concurrency coordinator and deferred futures.
- The SDK must preserve family-specific plugin-hook and late-child-checkpoint behavior through explicit policies.

**Compatibility requirements:**

- Existing operation interfaces, configs, overloads, results, exceptions, and behavior remain unchanged.
- Built-in migration must preserve exact operation IDs, topology, subtype strings, payloads, and replay behavior.
- Sequential reservations remain order-dependent.
- Custom-ID operations retain stable identities, but surrounding sequential IDs can change when definitions move.
- Reusing or changing a local ID is a workflow compatibility change.
- Changing a subtype string is a workflow compatibility change.
- Launching already reserved operations in a different order remains supported.

**Deferred:**

- A production DAG extension module.
- Propagating current context to application-created threads.
- User-defined backend operation types or checkpoint state machines.
