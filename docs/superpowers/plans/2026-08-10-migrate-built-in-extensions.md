# Built-In Extension Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite map, parallel, wait-for-callback, wait-for-condition, and with-retry through subtype-aware extension primitives while preserving all existing APIs, checkpoints, replay behavior, exceptions, and plugin events.

**Architecture:** Extend reservations with custom local IDs and arbitrary subtype strings while keeping backend state machines SDK-owned. Generalize STEP and CONTEXT primitives with extension-only state, replay, and failure policies, then make legacy `DurableContext` methods and static facades delegate to one implementation per built-in family. Map and parallel share a non-operation concurrency coordinator that waits through suspension-aware public durable-future combinators.

**Tech Stack:** Java 17, Maven reactor, JUnit 6, Mockito 5, `LocalDurableTestRunner`, Palantir Java Format through Spotless.

## Global Constraints

- Do not remove or change any existing method signature on `DurableContext`, `ParallelDurableFuture`, `ExtensionContext`, or `ExtensionOperation`.
- Do not add fields or methods to existing operation configuration classes.
- New subtype strings are non-null, nonblank, and are not restricted to `OperationSubType`.
- Primitive selectors determine backend operation types; extension code cannot emit raw checkpoint actions.
- Custom operation IDs are local values that replace a sequence number and are hashed with the current context prefix.
- Preserve exact built-in operation IDs, names, types, subtypes, parent IDs, payloads, statuses, replay behavior, exceptions, and plugin ordering.
- Do not add dependencies.
- Run `mvn spotless:apply` after Java changes.

---

### Task 1: Custom Local Operation IDs

**Files:**
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/execution/OperationIdGenerator.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionContext.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/execution/OperationIdGeneratorTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/ExtensionOperationImplTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/ExtensionOperationIntegrationTest.java`

**Interfaces:**
- Consumes: Existing `OperationIdGenerator.nextOperationId()` and `ExtensionContext.reserve(String)`.
- Produces: `OperationIdGenerator.nextOperationId(String localOperationId)` and `ExtensionContext.reserve(String name, String localOperationId)`.

- [ ] **Step 1: Write failing generator tests**

Add tests covering generated/custom interleaving:

```java
@Test
void customLocalIdsUseContextPrefixAndAdvanceSequence() {
    var root = new OperationIdGenerator(null);

    assertEquals(hashOperationId("node-a"), root.nextOperationId("node-a"));
    assertEquals(hashOperationId("2"), root.nextOperationId());
}

@Test
void generatedIdsSkipCustomNumericIds() {
    var generator = new OperationIdGenerator(null);

    assertEquals(hashOperationId("2"), generator.nextOperationId("2"));
    assertEquals(hashOperationId("3"), generator.nextOperationId());
}

@Test
void duplicateLocalIdsFail() {
    var generator = new OperationIdGenerator("parent");
    generator.nextOperationId("node");

    assertThrows(IllegalArgumentException.class, () -> generator.nextOperationId("node"));
}
```

Also cover null, blank, a direct generated ID followed by the same custom numeric ID, and
`hashOperationId("parent-node")` for child contexts.

- [ ] **Step 2: Run the generator tests and verify RED**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-pl sdk -Dtest=OperationIdGeneratorTest test
```

Expected: compilation fails because the custom-local-ID overload does not exist.

- [ ] **Step 3: Implement shared local-ID allocation**

Use one atomic counter and concurrent local-ID set:

```java
private final Set<String> allocatedLocalIds = ConcurrentHashMap.newKeySet();

public String nextOperationId() {
    String localId;
    do {
        localId = String.valueOf(operationCounter.incrementAndGet());
    } while (!allocatedLocalIds.add(localId));
    return hashOperationId(operationIdPrefix + localId);
}

public String nextOperationId(String localOperationId) {
    validateLocalOperationId(localOperationId);
    if (!allocatedLocalIds.add(localOperationId)) {
        throw new IllegalArgumentException("Local operation ID is already in use: " + localOperationId);
    }
    operationCounter.incrementAndGet();
    return hashOperationId(operationIdPrefix + localOperationId);
}
```

Validate before advancing the counter.

- [ ] **Step 4: Add the reservation overload**

Add an additive method to `ExtensionContext`:

```java
ExtensionOperation reserve(String name, String localOperationId);
```

Implement it in `DurableContextImpl` by validating the name and allocating through the new generator overload.
Keep `reserve(String)` unchanged.

- [ ] **Step 5: Add reservation and integration tests**

Assert:

- custom reservations are one-shot
- custom IDs remain stable when their registration order changes
- nested custom IDs use the child context ID prefix
- ordinary core operations and reservations share the same local-ID registry

- [ ] **Step 6: Run focused tests and commit**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk,sdk-integration-tests -am \
-Dtest=OperationIdGeneratorTest,ExtensionOperationImplTest,ExtensionOperationIntegrationTest \
-Dsurefire.failIfNoSpecifiedTests=false test
```

Commit:

```bash
git add sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "feat: add custom extension operation ids"
```

---

### Task 2: Arbitrary Primitive Subtype Strings

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/model/OperationDescriptor.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/ExtensionOperationImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/BaseDurableOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/StepOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/WaitOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/InvokeOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/CallbackOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/ChildContextOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/plugin/PluginInfoConverter.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/ExtensionOperationImplTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/plugin/PluginInfoConverterTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/ExtensionOperationIntegrationTest.java`

**Interfaces:**
- Consumes: One-shot `ExtensionOperation` selectors and existing enum-based `OperationIdentifier`.
- Produces: Additive subtype overloads for all primitive selectors and an internal identity carrying `OperationType` plus exact subtype string.

- [ ] **Step 1: Write failing subtype tests**

Add one test per primitive:

```java
var step = context.reserve("custom-step");
step.stepAsync("AcmeStep", String.class, () -> "done");

verify(context).stepAsyncWithId(
        eq("1"),
        eq("custom-step"),
        eq("AcmeStep"),
        eq(TypeToken.get(String.class)),
        any(),
        any());
```

Add integration assertions that checkpoints and plugin events contain `AcmeStep`, `AcmeWait`, `AcmeInvoke`,
`AcmeCallback`, and `AcmeContext`, while their operation types remain fixed by the selector.

- [ ] **Step 2: Run focused tests and verify RED**

Expected: compilation fails on missing subtype overloads.

- [ ] **Step 3: Add internal string-based identity**

Create:

```java
public record OperationDescriptor(
        String operationId,
        String name,
        OperationType operationType,
        String subType) {

    public OperationDescriptor {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
        if (subType == null || subType.isBlank()) {
            throw new IllegalArgumentException("subType cannot be null or blank");
        }
    }

    public static OperationDescriptor from(OperationIdentifier identifier) {
        return new OperationDescriptor(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType(),
                identifier.subType().getValue());
    }
}
```

Keep `OperationIdentifier` unchanged. Add descriptor constructor overloads to primitive operation classes while
retaining enum-based constructors for current call sites and tests.

- [ ] **Step 4: Generalize base replay and plugin paths**

Store `OperationDescriptor` in `BaseDurableOperation`. Use `descriptor.subType()` for updates and replay validation.
Retain:

```java
public OperationSubType getSubType()
```

for known enum-based operations, and add:

```java
public String getSubTypeValue()
```

for arbitrary values. Add descriptor overloads to `PluginInfoConverter` without changing existing overloads.

- [ ] **Step 5: Add subtype-aware selector overloads**

For each primitive, add additive methods such as:

```java
<T> DurableFuture<T> stepAsync(
        String subType,
        TypeToken<T> resultType,
        Supplier<T> function,
        StepConfig config);
```

Existing methods delegate using `OperationSubType.STEP.getValue()` and equivalent standard values. Validate subtype
before claiming the reservation so invalid input does not consume it.

- [ ] **Step 6: Run focused tests and commit**

Run the extension, primitive operation, replay-validation, and plugin converter unit tests plus
`ExtensionOperationIntegrationTest`.

Commit:

```bash
git add sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "feat: support custom extension subtypes"
```

---

### Task 3: Stateful STEP Extension Primitive

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionStepFunction.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionStepResult.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/config/ExtensionStepConfig.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/ExtensionOperationImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/StepOperation.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/ExtensionStepResultTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/config/ExtensionStepConfigTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/operation/StepOperationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/ExtensionOperationIntegrationTest.java`

**Interfaces:**
- Consumes: Subtype-aware STEP reservations.
- Produces: A fixed STEP lifecycle whose user outcome is `succeed(value)` or `retry(state, delay)`.

- [ ] **Step 1: Add failing API and validation tests**

Test immutable factories and builder defaults:

```java
var retry = ExtensionStepResult.retry("next", Duration.ofSeconds(2));
assertEquals("next", retry.state());
assertEquals(Duration.ofSeconds(2), retry.delay());
```

Reject null delays, negative delays, null results, and missing `ExtensionStepConfig`.

- [ ] **Step 2: Add failing state-machine tests**

Exercise:

- first attempt receives `initialState`
- retry checkpoints serialized state and delay
- READY replay resumes with checkpointed state and incremented attempt
- success returns the final state
- user exception checkpoints failure
- suspension and unrecoverable exceptions propagate

- [ ] **Step 3: Implement extension types**

Use a sealed result:

```java
public sealed interface ExtensionStepResult<T>
        permits ExtensionStepResult.Succeeded, ExtensionStepResult.Retry {
    record Succeeded<T>(T value) implements ExtensionStepResult<T> {}
    record Retry<T>(T state, Duration delay) implements ExtensionStepResult<T> {}
}
```

Implement `ExtensionStepConfig<T>` with builder fields `initialState` and `serDes`; null SerDes uses the durable
configuration default.

- [ ] **Step 4: Generalize StepOperation**

Introduce an internal attempt strategy inside `StepOperation`:

```java
private interface AttemptBehavior<T> {
    AttemptOutcome<T> execute(T state, StepContext context);
}
```

The existing constructor wraps the current function and retry strategy. The extension constructor maps
`ExtensionStepResult` onto the same START/RETRY/READY/SUCCEED/FAIL paths. Do not duplicate checkpoint sending or poll
logic.

- [ ] **Step 5: Expose the reservation selector**

Add:

```java
<T> DurableFuture<T> stepAsync(
        String subType,
        TypeToken<T> resultType,
        ExtensionStepFunction<T> function,
        ExtensionStepConfig<T> config);
```

The function receives state only. `StepContext` remains TLS-bound.

- [ ] **Step 6: Run focused tests and commit**

Run `ExtensionStepResultTest`, `ExtensionStepConfigTest`, `StepOperationTest`,
`ExtensionOperationImplTest`, and `ExtensionOperationIntegrationTest`.

Commit:

```bash
git add sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "feat: add stateful extension steps"
```

---

### Task 4: Configurable CONTEXT Extension Primitive

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionContextFunction.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionContextResult.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionContextReplayContext.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionContextErrorHandler.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionContextFailure.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionChildOperationSummary.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/config/ExtensionContextConfig.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/ExtensionOperationImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/ChildContextOperation.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/ExtensionContextResultTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/ExtensionContextReplayContextTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/config/ExtensionContextConfigTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/operation/ChildContextOperationTest.java`

**Interfaces:**
- Consumes: Subtype-aware CONTEXT reservations.
- Produces: Replay-state result policies, scoped replay TLS, configurable fallback failure translation, plugin-hook policy, and late-child checkpoint suppression.

- [ ] **Step 1: Write failing value and config tests**

Test these factories:

```java
ExtensionContextResult.completed(fullResult);
ExtensionContextResult.replayChildren(fullResult, replayState);
ExtensionContextResult.replayChildrenAboveSize(fullResult, replayState, 256 * 1024);
```

Test `ExtensionContextConfig.builder()` defaults:

```java
assertTrue(config.emitUserFunctionEvents());
assertFalse(config.suppressLateChildCheckpoints());
assertNotNull(config.childContextConfig());
```

- [ ] **Step 2: Write failing CONTEXT lifecycle tests**

Add tests for:

- normal result payload
- always replay-children with replay state
- threshold evaluated against serialized full result
- replay state available only inside `ExtensionContextReplayContext`
- nested replay scopes restore prior values
- framework hook emission enabled and disabled
- original exception reconstruction before fallback handler
- fallback handler receives child summaries
- default `ChildContextFailedException`
- a child finishing after a suppressing parent does not checkpoint

- [ ] **Step 3: Implement extension context value types**

Make all values immutable and defensively copy child summary lists. The replay TLS follows the existing
`OperationContextStorage` scoped-attachment pattern.

- [ ] **Step 4: Generalize ChildContextOperation**

Retain the existing `RunInChildContextConfig` constructor and adapt it to a standard context policy. Add an extension
constructor accepting `ExtensionContextFunction<T>` and `ExtensionContextConfig`.

On success:

```java
var outcome = extensionFunction.apply();
var full = serializeAndDeserializeResult(outcome.result());
var checkpoint = selectCheckpointPayload(outcome, full.serialized());
```

On replay with `replayChildren=true`, deserialize the stored replay state and attach it while rerunning the framework
function. Apply `emitUserFunctionEvents` only around the framework callback. Continue firing nested primitive hooks.

- [ ] **Step 5: Generalize parent completion suppression**

Replace the `ConcurrencyOperation<?>`-specific parent constructor dependency with a general parent completion owner.
Store the owning extension context operation on child `DurableContextImpl` instances when
`suppressLateChildCheckpoints` is enabled. Nested extension child operations consult this owner before checkpointing.

- [ ] **Step 6: Expose the advanced context selector**

Add:

```java
<T> DurableFuture<T> runInChildContextAsync(
        String subType,
        TypeToken<T> resultType,
        ExtensionContextFunction<T> function,
        ExtensionContextConfig config);
```

Keep the standard subtype plus `Supplier<T>` overload and all existing methods unchanged.

- [ ] **Step 7: Run focused tests and commit**

Run the new extension context tests, `ChildContextOperationTest`, `ExtensionOperationImplTest`, plugin tests, and
extension integration tests.

Commit:

```bash
git add sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "feat: add configurable extension contexts"
```

---

### Task 5: Migrate Wait for Callback

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/WaitForCallbackExtension.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableWaitForCallbackOperations.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/WaitForCallbackExtensionTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/DurableWaitForCallbackOperationsTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/CallbackIntegrationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/StaticOperationsIntegrationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/PluginIntegrationTest.java`

**Interfaces:**
- Consumes: Advanced CONTEXT reservation, callback and step primitives, and configurable context failure translation.
- Produces: One canonical wait-for-callback implementation used by static and legacy APIs.

- [ ] **Step 1: Write failing canonical-delegation tests**

Assert both entry points call a handler with the same names and topology:

```text
approval                  CONTEXT / WaitForCallback
  approval-callback       CALLBACK / Callback
  approval-submitter      STEP / Step
```

Cover callback failure, timeout, submitter failure, generic result types, custom SerDes, and plugin event ordering.

- [ ] **Step 2: Implement WaitForCallbackExtension**

Use:

```java
public static <T> DurableFuture<T> execute(
        ExtensionContext context,
        String name,
        TypeToken<T> resultType,
        BiConsumer<String, StepContext> submitter,
        WaitForCallbackConfig config)
```

Reserve the parent first, then create callback and submitter reservations inside its framework function. Configure
parent user-function hooks as enabled. Supply an error handler that inspects child summaries and recreates
`CallbackFailedException`, `CallbackTimeoutException`, and `CallbackSubmitterException`.

- [ ] **Step 3: Redirect both APIs**

`DurableContextImpl.waitForCallbackAsync` delegates with `this`. The static facade resolves
`ExtensionContext.getCurrentContext()` and retains its existing TLS adapter around the `Runnable`.

- [ ] **Step 4: Run callback suites and commit**

Run callback unit, integration, retry-with-callback, static operations, and plugin tests.

Commit:

```bash
git add sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "refactor: implement wait for callback as extension"
```

---

### Task 6: Migrate With Retry

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/WithRetryExtension.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableWithRetryOperations.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/WithRetryExtensionTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/DurableContextWithRetryTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/RetryInvokeIntegrationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/RetryWaitForCallbackIntegrationTest.java`

**Interfaces:**
- Consumes: Advanced `WithRetry` context reservation and ordinary wait primitives.
- Produces: One retry loop shared by static and legacy APIs.

- [ ] **Step 1: Write failing parity tests**

Cover first-attempt success, delayed retries, exhausted retries, null names, virtual versus checkpointed context,
control-flow exception propagation, and static TLS attempt metadata.

- [ ] **Step 2: Implement WithRetryExtension**

Move the retry loop out of `DurableContextImpl`:

```java
public static <T> DurableFuture<T> execute(
        ExtensionContext context,
        String name,
        BiFunction<Integer, DurableContext, T> operation,
        WithRetryConfig config)
```

Create a `WithRetry` extension context using the existing naming and virtual-context rules. Read the child
`DurableContext` through TLS, run the operation, and reserve ordinary waits with the existing backoff names.

- [ ] **Step 3: Redirect APIs and remove old loop helpers**

Delegate both legacy and static APIs to the canonical extension. Delete retry constants and loop methods from
`DurableContextImpl` after tests compile.

- [ ] **Step 4: Run retry suites and commit**

Commit:

```bash
git add sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "refactor: implement retry as extension"
```

---

### Task 7: Migrate Wait for Condition

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/WaitForConditionExtension.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/WaitForConditionFuture.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableWaitForConditionOperations.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Delete: `sdk/src/main/java/software/amazon/lambda/durable/operation/WaitForConditionOperation.java`
- Move/replace test: `sdk/src/test/java/software/amazon/lambda/durable/operation/WaitForConditionOperationTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/WaitForConditionExtensionTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/WaitForConditionIntegrationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/PluginIntegrationTest.java`

**Interfaces:**
- Consumes: Stateful extension STEP.
- Produces: Existing wait-for-condition APIs and exception behavior through subtype `WaitForCondition`.

- [ ] **Step 1: Write failing extension parity tests**

Assert the existing single STEP checkpoint is retained across immediate success, multiple retries, READY replay,
initial state, custom strategy, custom SerDes, thrown checks, and plugin attempt numbers.

- [ ] **Step 2: Implement WaitForConditionExtension**

Map the existing result to fixed step outcomes:

```java
var result = checkFunction.apply(state, StepContext.getCurrentContext());
if (result.isDone()) {
    return ExtensionStepResult.succeed(result.value());
}
var delay = config.waitStrategy().evaluate(
        result.value(),
        StepContext.getCurrentContext().getAttempt());
return ExtensionStepResult.retry(result.value(), delay);
```

Use subtype `WaitForCondition`, the existing initial state, and the existing SerDes defaulting.

- [ ] **Step 3: Preserve fallback exception type**

Wrap the stateful step future in `WaitForConditionFuture`. Delegate `completionFuture()`. In `get()`, let original
deserialized exceptions propagate; translate only fallback `StepFailedException` to
`WaitForConditionFailedException` using its operation.

- [ ] **Step 4: Redirect APIs and remove the specialized operation**

Delegate static and legacy methods to the canonical extension. Delete `WaitForConditionOperation` after all its
state-machine assertions have equivalent coverage in `StepOperationTest` and `WaitForConditionExtensionTest`.

- [ ] **Step 5: Run condition suites and commit**

Commit:

```bash
git add -A sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "refactor: implement wait for condition as extension"
```

---

### Task 8: Suspension-Aware Concurrency Coordination

**Files:**
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableFuture.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/DeferredDurableFuture.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/ExtensionConcurrencyCoordinator.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/DurableFutureTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/DeferredDurableFutureTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/ExtensionConcurrencyCoordinatorTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/ExtensionConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: `DurableFuture.completionFuture()`, extension child reservations, and `CompletionConfig`.
- Produces: A non-operation coordinator usable by map and parallel without internal future downcasts.

- [ ] **Step 1: Write failing suspension tests for anyOf**

Use `LocalDurableTestRunner` with child futures waiting on callbacks. Assert the invocation reaches `PENDING` instead
of remaining active while `DurableFuture.anyOf` waits.

- [ ] **Step 2: Make anyOf cooperate with SDK thread registration**

When called on an SDK-managed context thread, delegate completion waiting to a context helper that:

1. records the current thread context
2. registers a completion continuation
3. deregisters the active thread before blocking
4. re-registers when any completion signal fires

Keep current behavior outside SDK threads. Do not change `completionFuture()` mutation isolation.

- [ ] **Step 3: Implement DeferredDurableFuture**

Provide a one-time `bind(DurableFuture<T>)` method. `get()` waits for binding then delegates. `completionFuture()`
returns a stable future completed from the bound future. Reject a second binding.

- [ ] **Step 4: Implement ExtensionConcurrencyCoordinator**

The coordinator maintains:

```java
record Item<T>(
        ExtensionOperation reservation,
        Supplier<DurableFuture<T>> launcher,
        DeferredDurableFuture<T> exposedFuture) {}
```

It must:

- register items in deterministic order
- launch no more than `maxConcurrency`
- wait through `DurableFuture.anyOf`
- count succeeded and failed items
- evaluate `CompletionConfig.completionDecisionFunction()`
- preserve `allItemsRegistered`
- mark pending/running incomplete items as skipped when completion occurs
- propagate suspension and unrecoverable control flow

- [ ] **Step 5: Run focused and integration tests and commit**

Commit:

```bash
git add sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "feat: add extension concurrency coordination"
```

---

### Task 9: Migrate Map

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/MapExtension.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableMapOperations.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Delete: `sdk/src/main/java/software/amazon/lambda/durable/operation/MapOperation.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/MapExtensionTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/DurableMapOperationsTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/MapIntegrationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/MapInputValidationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/PluginIntegrationTest.java`

**Interfaces:**
- Consumes: Extension CONTEXT result policies and the shared concurrency coordinator.
- Produces: Existing map behavior with `Map` and `MapIteration` subtype-aware context primitives.

- [ ] **Step 1: Add checkpoint-history parity tests**

For legacy and static map calls, assert identical:

- parent and iteration IDs
- `Map` and `MapIteration` subtypes
- nested/flat parent IDs
- small result payloads
- large result replay state and `replayChildren`
- early completion statuses and skipped iterations
- empty-map checkpoint flag behavior
- plugin events

- [ ] **Step 2: Implement MapExtension**

Use:

```java
public static <I, O> DurableFuture<MapResult<O>> execute(
        ExtensionContext context,
        String name,
        Collection<I> items,
        TypeToken<O> resultType,
        DurableContext.MapFunction<I, O> function,
        MapConfig config)
```

Validate and copy items exactly as the current implementation. Reserve the `Map` parent first. Inside it, reserve
iterations in input order, attach `MapItemContext`, and launch through `ExtensionConcurrencyCoordinator`.

- [ ] **Step 3: Preserve result and replay policies**

Construct `MapResult` with existing success, failure, and skipped entries. For results at least 256 KB, use:

```java
ExtensionContextResult.replayChildrenAboveSize(
        fullResult,
        stripMapResult(fullResult),
        256 * 1024);
```

On replay, use `ExtensionContextReplayContext` statuses to avoid launching previously skipped iterations and to
restore the prior completion decision.

- [ ] **Step 4: Preserve empty-map and plugin behavior**

Consume the parent reservation in all cases. Use a virtual `Map` extension context when empty-map checkpointing is
disabled, retain the warning, return `MapResult.empty()`, suppress parent framework user-function hooks, and keep
operation start/end plugin events balanced.

- [ ] **Step 5: Redirect APIs and delete MapOperation**

Delegate static and legacy map methods to `MapExtension`. Move reusable result assertions from operation tests into
`MapExtensionTest`. Delete `MapOperation`.

- [ ] **Step 6: Run the complete map suite and commit**

Run all map unit/integration tests and map-related plugin tests for both nesting modes.

Commit:

```bash
git add -A sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "refactor: implement map as extension"
```

---

### Task 10: Migrate Parallel and Remove Specialized Concurrency

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/ParallelExtension.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/context/extension/ParallelExtensionFuture.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableParallelOperations.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Delete: `sdk/src/main/java/software/amazon/lambda/durable/operation/ParallelOperation.java`
- Delete: `sdk/src/main/java/software/amazon/lambda/durable/operation/ConcurrencyOperation.java`
- Delete/replace test: `sdk/src/test/java/software/amazon/lambda/durable/operation/ParallelOperationTest.java`
- Delete/replace test: `sdk/src/test/java/software/amazon/lambda/durable/operation/ConcurrencyOperationTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/ParallelExtensionTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/extension/ExtensionConcurrencyCoordinatorTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/ParallelIntegrationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/PluginIntegrationTest.java`

**Interfaces:**
- Consumes: Dynamic coordinator registration, deferred futures, and advanced CONTEXT replay policies.
- Produces: Existing `ParallelDurableFuture` backed entirely by extension primitives.

- [ ] **Step 1: Add parallel checkpoint-history parity tests**

Compare legacy and static APIs for empty, heterogeneous, max-concurrency, early-success, failure-tolerance, nested,
flat, replay, branches added after parent completion, and plugin scenarios.

- [ ] **Step 2: Implement ParallelExtensionFuture**

The future:

- starts one `Parallel` extension context
- queues branch definitions in registration order
- returns a `DeferredDurableFuture` from each branch call
- rejects registration after `get()` or `close()`
- signals `allItemsRegistered` on join
- delegates its own `completionFuture()` and `get()` to the parent context future

The parent framework function obtains its child `ExtensionContext`, drains registrations through the coordinator,
and returns `ExtensionContextResult.replayChildren(result, result)`.

- [ ] **Step 3: Preserve replay and late-completion behavior**

Use stored `ParallelResult.statuses()` to skip branches that did not exist or were previously skipped. Configure the
parent with framework user hooks disabled and late-child checkpoints suppressed. Configure branch contexts with
`ParallelBranch` fallback translation and virtual mode for flat nesting.

- [ ] **Step 4: Redirect APIs and delete specialized classes**

`DurableContextImpl.parallel` and `DurableParallelOperations.parallel` instantiate the same canonical extension
future. Delete `ParallelOperation` and `ConcurrencyOperation` after moving all shared assertions to coordinator and
extension tests.

- [ ] **Step 5: Run parallel and broad integration suites and commit**

Run parallel unit/integration tests, nested map/parallel tests, callbacks inside branches, condition operations inside
branches, and plugin tests.

Commit:

```bash
git add -A sdk/src/main sdk/src/test sdk-integration-tests/src/test
git commit -m "refactor: implement parallel as extension"
```

---

### Task 11: API Parity, Documentation, and Full Verification

**Files:**
- Modify: `docs/advanced/extensions.md`
- Modify: `docs/adr/006-custom-extension-operations.md`
- Modify: `README.md` only if the extension guide link or description changes
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/StaticOperationsIntegrationTest.java`
- Test: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/PluginIntegrationTest.java`

**Interfaces:**
- Consumes: All migrated built-in extensions.
- Produces: Final compatibility evidence and documented public extension contracts.

- [ ] **Step 1: Add final legacy/static parity coverage**

For each family, run the legacy and static forms with equivalent inputs and compare normalized operation history:

```java
record OperationShape(
        String name,
        String type,
        String subType,
        String parentId,
        String status) {}
```

Also verify custom subtype strings and custom local IDs from a separate Maven module fixture.

- [ ] **Step 2: Verify public API compatibility**

Confirm:

```bash
git diff 1d3de02 -- sdk/src/main/java/software/amazon/lambda/durable/DurableContext.java
git diff 1d3de02 -- sdk/src/main/java/software/amazon/lambda/durable/ParallelDurableFuture.java
git diff 1d3de02 -- sdk/src/main/java/software/amazon/lambda/durable/config
```

Expected: no removed or changed existing signatures or fields; only additive extension-specific files and overloads.

- [ ] **Step 3: Update extension documentation**

Document:

- arbitrary subtype strings
- custom local ID hashing and collision rules
- stateful STEP outcomes
- context replay state
- context error handlers
- built-in operations as reference extensions
- subtype, local ID, and replay state compatibility warnings

- [ ] **Step 4: Run Spotless**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 spotless:apply
```

Review and remove only unrelated formatter churn outside touched files.

- [ ] **Step 5: Run focused dependency closure**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk,sdk-integration-tests -am test
```

Expected: all SDK, testing, and integration tests pass.

- [ ] **Step 6: Run full reactor**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
clean install
```

Expected: all eight reactor modules succeed. Cloud tests remain disabled by their existing guard.

- [ ] **Step 7: Final audit and commit**

Run:

```bash
git diff --check
git status --short
rg -n "MapOperation|ParallelOperation|ConcurrencyOperation|WaitForConditionOperation" sdk/src/main sdk/src/test
```

Expected: no obsolete specialized engine references and no unintended worktree changes.

Commit:

```bash
git add README.md docs sdk sdk-integration-tests
git commit -m "docs: document migrated built-in extensions"
```
