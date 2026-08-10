# Custom Extension Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a supported public API for context-free static durable operations and replay-safe custom extensions while leaving the existing `DurableContext` interface surface unchanged.

**Architecture:** SDK-managed handler and child contexts implement a minimal `ExtensionContext` that can reserve opaque, one-shot primitive identities. `DurableCoreOperations` and one facade per built-in extension family adapt context-free user callbacks to the existing `DurableContext` implementation, while typed scoped TLS contexts expose SDK-generated metadata. Existing primitive operation classes continue to own checkpointing, replay, suspension, serialization, and plugin events.

**Tech Stack:** Java 17, Maven reactor, JUnit 6, Mockito 5, `LocalDurableTestRunner`, Palantir Java Format through Spotless.

## Global Constraints

- Keep all existing `DurableContext` methods and callback signatures unchanged for backward compatibility.
- Do not add `runExtensionAsync` or any other extension entry point to `DurableContext`.
- New user callbacks receive only application-provided values; SDK contexts and generated metadata are retrieved through TLS.
- Keep primitive operation IDs opaque and SDK-owned.
- A reservation is one-shot and allocates its sequential ID when `reserve` is called, not when the primitive is launched.
- Extensions do not automatically create child contexts.
- Do not add dependencies.
- Run `mvn spotless:apply` after Java changes.

---

### Task 1: Scoped Current Contexts

**Files:**
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/BaseContextImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/execution/DurableExecutor.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/ChildContextOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/StepOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/WaitForConditionOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableContext.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/OperationContextStorage.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/MapItemContext.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/WaitForCallbackContext.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/WithRetryContext.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/CurrentContextTest.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/OperationContextStorageTest.java`

**Interfaces:**
- Consumes: Existing `BaseContext.getCurrentContext()`, `DurableContext.getCurrentContext()`, and `StepContext.getCurrentContext()`.
- Produces: `MapItemContext.getCurrentContext().getIndex()`, `WaitForCallbackContext.getCurrentContext().getCallbackId()`, and `WithRetryContext.getCurrentContext().getAttempt()`.

- [ ] **Step 1: Write failing lookup and restoration tests**

Add tests that establish these behaviors:

```java
@Test
void mapItemContextRestoresNestedScope() {
    assertThrows(IllegalStateException.class, MapItemContext::getCurrentContext);
    try (var outer = MapItemContext.attach(2)) {
        assertEquals(2, MapItemContext.getCurrentContext().getIndex());
        try (var inner = MapItemContext.attach(7)) {
            assertEquals(7, MapItemContext.getCurrentContext().getIndex());
        }
        assertEquals(2, MapItemContext.getCurrentContext().getIndex());
    }
    assertThrows(IllegalStateException.class, MapItemContext::getCurrentContext);
}
```

Add equivalent outside-scope and nested-restoration assertions for callback IDs and retry attempts. In
`CurrentContextTest`, assert that handler/child contexts resolve as `DurableContext`, step scopes reject
`DurableContext` with guidance to use `StepContext`, and all scopes restore the preceding base context.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk -Dtest=CurrentContextTest,OperationContextStorageTest test
```

Expected: compilation fails because the three operation-specific context classes and their scoped attachment methods
do not exist.

- [ ] **Step 3: Implement scoped storage and SDK binding**

Implement a package-private generic storage helper:

```java
final class OperationContextStorage<T> {
    private final String contextName;
    private final ThreadLocal<T> current = new ThreadLocal<>();

    T getCurrentContext() {
        var context = current.get();
        if (context == null) {
            throw new IllegalStateException(contextName + " is not active on the current thread");
        }
        return context;
    }

    SafeCloseable attach(T context) {
        var previous = current.get();
        current.set(context);
        return () -> {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        };
    }
}
```

Each public final metadata context owns a private static storage, a private immutable value, a public static lookup,
a public getter, and a package-private `attach` used by same-package facades. Preserve the existing
`DurableContext` signatures while clarifying its current-context failure behavior. Bind base contexts with
try-with-resources around handler, child, step, and condition user functions so nested calls restore rather than
blindly clear TLS.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all current-context and operation-context tests pass.

- [ ] **Step 5: Commit**

```bash
git add sdk/src/main/java/software/amazon/lambda/durable \
        sdk/src/test/java/software/amazon/lambda/durable/CurrentContextTest.java \
        sdk/src/test/java/software/amazon/lambda/durable/OperationContextStorageTest.java
git commit -m "feat: add scoped durable operation contexts"
```

### Task 2: Opaque Primitive Reservations

**Files:**
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionContext.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/ExtensionOperation.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/DurableContextImpl.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/context/ExtensionOperationImpl.java`
- Delete: `sdk/src/main/java/software/amazon/lambda/durable/context/ExtensionContextImpl.java`
- Delete: `sdk/src/main/java/software/amazon/lambda/durable/DurableExtensions.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableContext.java`
- Test: `sdk/src/test/java/software/amazon/lambda/durable/context/ExtensionOperationImplTest.java`
- Delete: `sdk/src/test/java/software/amazon/lambda/durable/context/ExtensionContextImplTest.java`

**Interfaces:**
- Consumes: `DurableContextImpl` primitive construction and `OperationIdGenerator`.
- Produces:

```java
public interface ExtensionContext extends BaseContext {
    static ExtensionContext getCurrentContext();
    boolean isReplaying();
    ExtensionOperation reserve(String name);
}
```

```java
public interface ExtensionOperation {
    <T> DurableFuture<T> stepAsync(
            TypeToken<T> resultType, Supplier<T> function, StepConfig config);
    DurableFuture<Void> waitAsync(Duration duration);
    <T, U> DurableFuture<T> invokeAsync(
            String functionName, U payload, TypeToken<T> resultType, InvokeConfig config);
    <T> DurableCallbackFuture<T> createCallback(
            TypeToken<T> resultType, CallbackConfig config);
    <T> DurableFuture<T> runInChildContextAsync(
            TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config);
}
```

- [ ] **Step 1: Write failing reservation tests**

Create tests that use a real `DurableContextImpl` with mocked dependencies to prove:

```java
var first = context.reserve("first");
var second = context.reserve("second");

second.stepAsync(String.class, () -> "second");
first.stepAsync(String.class, () -> "first");

verify(operationFactory).createStepOperation("2", "second", ...);
verify(operationFactory).createStepOperation("1", "first", ...);
```

Also test every reserved primitive path, `ExtensionContext.getCurrentContext()` on handler/child versus step/no scope,
and a second use of the same reservation throwing:

```java
assertThrows(IllegalStateException.class, () -> reservation.waitAsync(Duration.ZERO));
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk -Dtest=ExtensionOperationImplTest,CurrentContextTest test
```

Expected: compilation fails because reservations still accept context-bearing functions and
`DurableContextImpl` does not directly implement the final `ExtensionContext` contract.

- [ ] **Step 3: Implement the minimal reservation path**

Make `DurableContextImpl` implement `ExtensionContext`, with:

```java
@Override
public ExtensionOperation reserve(String name) {
    return new ExtensionOperationImpl(this, operationIdGenerator.next(), name);
}
```

Retain package-private explicit-ID helpers for step, wait, invoke, callback, and child context. Adapt `Supplier<T>` to
the existing primitive callbacks inside `ExtensionOperationImpl`; current TLS is already attached when the user
supplier executes. Guard all primitive selectors with a single `AtomicBoolean.compareAndSet(false, true)`.

Remove `runExtensionAsync` from `DurableContext`, remove the universal `DurableExtensions` facade, remove the wrapper
`ExtensionContextImpl`, and keep `ExtensionContext` limited to current lookup, replay state, and reservation.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all reservation and current-context tests pass.

- [ ] **Step 5: Commit**

```bash
git add sdk/src/main/java/software/amazon/lambda/durable \
        sdk/src/test/java/software/amazon/lambda/durable/context
git commit -m "feat: add opaque extension operation reservations"
```

### Task 3: Context-Free Core Static Facade

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/DurableCoreOperations.java`
- Delete: `sdk/src/main/java/software/amazon/lambda/durable/DurableOperations.java`
- Create: `sdk/src/test/java/software/amazon/lambda/durable/DurableCoreOperationsTest.java`

**Interfaces:**
- Consumes: `DurableContext.getCurrentContext()` and all existing primitive instance methods.
- Produces: sync/async, `Class<T>`/`TypeToken<T>`, default/custom config overloads for `step`, `wait`, chained
  `invoke`, `createCallback`, and `runInChildContext`.

- [ ] **Step 1: Write failing facade tests**

Write tests against a TLS-bound mocked `DurableContext` proving the core overloads delegate and strip SDK context
parameters:

```java
var result = DurableCoreOperations.step("step", String.class, () -> {
    assertSame(stepContext, StepContext.getCurrentContext());
    return "done";
});
assertEquals("done", result);
```

For child contexts, verify a zero-argument supplier can obtain both `DurableContext` and `ExtensionContext` from TLS.
Also assert every facade family throws `IllegalStateException` outside a durable context.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk -Dtest=DurableCoreOperationsTest test
```

Expected: compilation fails because `DurableCoreOperations` does not exist.

- [ ] **Step 3: Implement only primitive facade overloads**

Create a stateless final utility class whose step methods accept `Supplier<T>` and delegate with
`ignored -> function.get()`. Child-context methods also accept `Supplier<T>` and delegate with
`ignored -> function.get()`. Invoke, wait, and callback methods delegate values unchanged. Do not include map,
parallel, callback composition, condition polling, or retry methods.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all core facade tests pass.

- [ ] **Step 5: Commit**

```bash
git add sdk/src/main/java/software/amazon/lambda/durable/DurableCoreOperations.java \
        sdk/src/main/java/software/amazon/lambda/durable/DurableOperations.java \
        sdk/src/test/java/software/amazon/lambda/durable/DurableCoreOperationsTest.java
git commit -m "feat: add context-free core operation facade"
```

### Task 4: Independently Maintained Extension Facades

**Files:**
- Create: `sdk/src/main/java/software/amazon/lambda/durable/DurableMapOperations.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/DurableParallelOperations.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/DurableWaitForCallbackOperations.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/DurableWaitForConditionOperations.java`
- Create: `sdk/src/main/java/software/amazon/lambda/durable/DurableWithRetryOperations.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/ParallelDurableFuture.java`
- Create: `sdk/src/test/java/software/amazon/lambda/durable/DurableMapOperationsTest.java`
- Create: `sdk/src/test/java/software/amazon/lambda/durable/DurableParallelOperationsTest.java`
- Create: `sdk/src/test/java/software/amazon/lambda/durable/DurableWaitForCallbackOperationsTest.java`
- Create: `sdk/src/test/java/software/amazon/lambda/durable/DurableWaitForConditionOperationsTest.java`
- Create: `sdk/src/test/java/software/amazon/lambda/durable/DurableWithRetryOperationsTest.java`

**Interfaces:**
- Consumes: Existing `DurableContext` map, parallel, wait-for-callback, wait-for-condition, and with-retry methods.
- Produces:
  - map callbacks as `Function<I, O>` with index in `MapItemContext`
  - parallel branch callbacks as `Supplier<T>`
  - callback submitters as `Runnable` with ID in `WaitForCallbackContext`
  - condition checks as `Function<T, WaitForConditionResult<T>>`
  - retry bodies as `Supplier<T>` with attempt in `WithRetryContext`

- [ ] **Step 1: Write one failing metadata test per facade**

Tests must invoke the adapted legacy callback and assert the public callback sees TLS metadata:

```java
DurableMapOperations.map("map", List.of("a"), String.class, item -> {
    assertEquals(3, MapItemContext.getCurrentContext().getIndex());
    return item.toUpperCase();
});
```

Use the mocked legacy callback to supply index `3`; repeat for callback ID `"cb-1"` and retry attempt `2`. For
wait-for-condition, bind a `StepContext` and prove the check function receives only the state value. For parallel,
compile and execute `parallel.branch("branch", String.class, () -> "done")`.

- [ ] **Step 2: Run the five focused tests and verify RED**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk -Dtest=DurableMapOperationsTest,DurableParallelOperationsTest,DurableWaitForCallbackOperationsTest,DurableWaitForConditionOperationsTest,DurableWithRetryOperationsTest test
```

Expected: compilation fails because the split facades and supplier branch overloads do not exist.

- [ ] **Step 3: Implement the five adapters**

Each class is a stateless final utility class containing only its named family. Adapt callbacks as follows:

```java
(item, index, ignored) -> {
    try (var scope = MapItemContext.attach(index)) {
        return function.apply(item);
    }
}
```

```java
(callbackId, ignored) -> {
    try (var scope = WaitForCallbackContext.attach(callbackId)) {
        submitter.run();
    }
}
```

```java
(attempt, ignored) -> {
    try (var scope = WithRetryContext.attach(attempt)) {
        return operation.get();
    }
}
```

The condition adapter is `(state, ignored) -> checkFunction.apply(state)` because `WaitForConditionOperation` binds
the active `StepContext`. Add default supplier overloads to `ParallelDurableFuture` that delegate to its existing
`Function<DurableContext, T>` core method.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all five facade tests pass.

- [ ] **Step 5: Commit**

```bash
git add sdk/src/main/java/software/amazon/lambda/durable \
        sdk/src/test/java/software/amazon/lambda/durable/Durable*OperationsTest.java
git commit -m "feat: split built-in extension operation facades"
```

### Task 5: Public Durable Future Completion Contract

**Files:**
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/DurableFuture.java`
- Modify: `sdk/src/main/java/software/amazon/lambda/durable/operation/BaseDurableOperation.java`
- Modify: `sdk/src/test/java/software/amazon/lambda/durable/DurableFutureTest.java`

**Interfaces:**
- Consumes: Existing `DurableFuture.anyOf` and SDK operation completion futures.
- Produces: `default CompletableFuture<Void> completionFuture()` for custom composed futures.

- [ ] **Step 1: Write a failing custom-future combinator test**

Create a test-only `DurableFuture<String>` whose result future and completion signal are independent, override
`completionFuture()`, pass it to `DurableFuture.anyOf`, complete the signal, and assert `anyOf` completes without
requiring the future to extend `BaseDurableOperation`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-pl sdk -Dtest=DurableFutureTest test
```

Expected: the test fails because `anyOf` still relies on an SDK-internal operation downcast or no public completion
method exists.

- [ ] **Step 3: Implement the completion signal**

Add the default method throwing `UnsupportedOperationException` to custom futures that do not opt in. Override it in
`BaseDurableOperation` by deriving `internalFuture.thenApply(ignored -> null)` so callers cannot complete or cancel
the underlying durable operation. Change `DurableFuture.anyOf` to collect `completionFuture()` values without an
internal type cast.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all durable future tests pass.

- [ ] **Step 5: Commit**

```bash
git add sdk/src/main/java/software/amazon/lambda/durable/DurableFuture.java \
        sdk/src/main/java/software/amazon/lambda/durable/operation/BaseDurableOperation.java \
        sdk/src/test/java/software/amazon/lambda/durable/DurableFutureTest.java
git commit -m "feat: expose durable future completion signals"
```

### Task 6: Separate-Module Proof Extension and Integration Semantics

**Files:**
- Modify: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/extension/PairOperations.java`
- Modify: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/ExtensionOperationIntegrationTest.java`
- Create: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/StaticOperationsIntegrationTest.java`
- Modify: `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/PluginIntegrationTest.java`

**Interfaces:**
- Consumes: Only public classes from the `sdk` artifact; the `sdk-integration-tests` Maven module is the external
  compilation boundary.
- Produces: A proof extension that reserves two step identities in registration order and can launch them in either
  order without changing replay identities.

- [ ] **Step 1: Write failing external-module integration tests**

Implement the test fixture call site before its production helper:

```java
var result = PairOperations.pair(
        "pair",
        () -> DurableCoreOperations.step("left-value", String.class, () -> "left"),
        () -> DurableCoreOperations.step("right-value", String.class, () -> "right"),
        true);
assertEquals(new PairResult("left", "right"), result);
```

Add tests using `LocalDurableTestRunner` for first execution plus replay, a wait-based suspension/resume, reverse launch
order, same-scope nested extension calls, an explicitly reserved child context, all static facade families, and TLS
metadata. Update the plugin test to assert primitive names are observed and no synthetic extension lifecycle event is
emitted.

- [ ] **Step 2: Run integration tests and verify RED**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk-integration-tests -am \
-Dtest=ExtensionOperationIntegrationTest,StaticOperationsIntegrationTest,PluginIntegrationTest \
-Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: tests fail because the external fixture still targets the discarded universal runner and context-bearing
callbacks.

- [ ] **Step 3: Implement the public-contract-only fixture**

`PairOperations` may import only public SDK types. It obtains `ExtensionContext.getCurrentContext()`, reserves
`left` and `right`, launches the selected reservation order, and combines the results. It must not import any package
under `software.amazon.lambda.durable.context`, `.execution`, or `.operation`.

- [ ] **Step 4: Run integration tests and verify GREEN**

Run the command from Step 2. Expected: all extension, static operation, and plugin integration tests pass.

- [ ] **Step 5: Commit**

```bash
git add sdk-integration-tests/src/test/java/software/amazon/lambda/durable
git commit -m "test: verify custom extensions across module boundary"
```

### Task 7: Documentation, Formatting, and Reactor Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/advanced/extensions.md`
- Modify: `docs/adr/006-custom-extension-operations.md`

**Interfaces:**
- Consumes: The final public API implemented by Tasks 1-6.
- Produces: User and extension-author documentation matching the code exactly.

- [ ] **Step 1: Rewrite the extension guide**

Document:

- static import examples for `DurableCoreOperations` and every split extension facade
- TLS-only user functions and all three metadata contexts
- ordinary static extension methods without `DurableExtensions.run`
- same-scope direct composition versus explicit child contexts
- deterministic reservation order and variable launch order
- one-shot reservation behavior
- public-contract-only separate Maven modules
- primitive-only plugin lifecycle events
- `DurableFuture.completionFuture()` for custom composed futures

Mark ADR-006 `Accepted` only after implementation and verification succeed.

- [ ] **Step 2: Format all Java sources**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 spotless:apply
```

Expected: Spotless exits successfully and only intended Java files change.

- [ ] **Step 3: Run focused SDK and integration verification**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
-pl sdk,sdk-integration-tests -am test
```

Expected: all tests in the dependency closure pass.

- [ ] **Step 4: Run the full reactor**

Run:

```bash
JAVA_HOME=/home/czk/.codex-tmp/jdk17 \
/home/czk/.codex-tmp/maven-3.9.11/bin/mvn \
-Dmaven.repo.local=/home/czk/.codex-tmp/m2 \
-Djacoco.skip=true \
-DargLine=-javaagent:/home/czk/.codex-tmp/m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
clean install
```

Expected: `BUILD SUCCESS`. Cloud example tests remain disabled.

- [ ] **Step 5: Review public compatibility and commit**

Verify:

```bash
git diff 6962f5a -- sdk/src/main/java/software/amazon/lambda/durable/DurableContext.java
rg -n "DurableExtensions|DurableOperations|runExtensionAsync" \
    sdk/src/main sdk/src/test sdk-integration-tests docs README.md
git status --short
```

Expected: `DurableContext` contains no new method signatures; discarded names have no live references; the status
contains only intended files.

Then commit:

```bash
git add README.md docs sdk sdk-integration-tests
git commit -m "docs: document custom extension operations"
```
