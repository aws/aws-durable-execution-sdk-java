# Design Document: Parallel Map Operation

## Overview

The `map()` operation is a data-driven concurrent execution primitive for the AWS Lambda Durable Execution Java SDK. It applies a single `MapFunction` across a collection of items concurrently, with each item executing as a `ChildContextOperation` with `OperationSubType.MAP_ITERATION`. Results are collected into a `BatchResult<T>` maintaining input order, with configurable completion criteria and per-item error isolation.

Both synchronous (`map`) and asynchronous (`mapAsync`) variants are provided.

### Architecture: BaseConcurrentOperation + ChildContextOperation

The `map()` operation follows the prototype's architecture:

- **`BaseConcurrentOperation<R>`** is an abstract class extending `BaseDurableOperation<R>` that provides the shared concurrent execution framework. It creates a root child context, manages a queue of `ChildContextOperation` instances, tracks success/failure counts, evaluates completion criteria, and handles concurrency limiting via an `activeBranches` counter. Both `MapOperation` and the future `ParallelOperation` extend this class.
- Each item runs as a **`ChildContextOperation`** with `OperationSubType.MAP_ITERATION`, created as a child of the root context. `ChildContextOperation` already handles running user code in a separate thread (via `DurableConfig.getExecutorService()`), creating child contexts with their own operation counter, checkpointing (start, succeed, fail), replay (cached results, replayChildren for large results), and suspend/resume (via `ExecutionManager`).
- A new **`MapOperation`** class extends `BaseConcurrentOperation` and provides map-specific logic: iterating over items, wrapping each item's `MapFunction` call into a `ChildContextOperation`, and aggregating results into `BatchResult`.
- **No separate thread pool** is created. The existing user-configured executor from `DurableConfig.getExecutorService()` is used (same one `ChildContextOperation` already uses).
- **Concurrency limiting** uses a queue + `activeBranches` counter (not a semaphore). When a branch completes (`onChildContextComplete` callback), the next queued branch is started — but only after the new branch's thread is registered (to prevent premature suspension).
- **Suspend/resume** is not our concern — `ExecutionManager` already handles this.
- **Thread registration ordering** is critical: when starting the next branch after one completes, the new branch's thread must be registered before the completed branch's thread is deregistered. Otherwise `ExecutionManager` might see zero active threads and suspend execution prematurely.

### Design Rationale

- `BaseConcurrentOperation` extends `BaseDurableOperation` because it integrates naturally into the SDK's operation lifecycle (start/replay/get) and follows the existing pattern of operations in the `operation/` package.
- The queue-based concurrency approach (instead of semaphore) is required because `execute()` is non-blocking — the calling thread cannot be blocked by a semaphore acquire.
- `MapOperation` creates N `ChildContextOperation` instances because `ChildContextOperation` already solves per-item execution, threading, checkpointing, and replay. Reimplementing this would violate DRY.
- The public API accepts `Collection<I>` with runtime validation rejecting known unordered types (e.g., `HashSet`) and documentation requiring deterministic iteration order. Internally converts to `List` via `List.copyOf(items)` for index-based access.

## Architecture

### Component Relationships

```
DurableContext.map() / mapAsync()
  │  (creates operationId, validates inputs, converts Collection to List)
  └── MapOperation extends BaseConcurrentOperation (new, in operation/ package)
        ├── Creates root child context
        ├── Creates N ChildContextOperation instances (one per item)
        ├── Queue-based concurrency limiting (activeBranches counter)
        ├── Completion evaluation (success/failure counts vs CompletionConfig)
        ├── onChildContextComplete callback: start next queued branch, evaluate completion
        ├── Aggregates results into BatchResult (map-specific)
        └── Handles map-specific checkpoint/replay

BaseConcurrentOperation extends BaseDurableOperation (new, shared):
  ├── Root child context creation
  ├── Queue + activeBranches counter for concurrency limiting
  ├── Success/failure tracking (AtomicInteger counters)
  ├── CompletionConfig evaluation (when to stop)
  ├── onChildContextComplete callback (thread registration ordering)
  └── Abstract: subclasses provide item/branch-specific logic

Reused (existing, no modifications):
  ├── ChildContextOperation — per-item execution, threading, checkpointing, replay
  ├── ExecutionManager — thread coordination, suspend/resume
  └── DurableConfig.getExecutorService() — user's thread pool
```

## Architecture

### Class Hierarchy

```
BaseDurableOperation<T>                          (existing abstract class)
  ├── StepOperation<T>                           (existing)
  ├── WaitOperation                              (existing)
  ├── InvokeOperation<T>                         (existing)
  ├── ChildContextOperation<T>                   (existing — used per-item)
  ├── CallbackOperation<T>                       (existing)
  └── BaseConcurrentOperation<R>                 (NEW abstract class)
        ├── MapOperation<I,O>                    (NEW — extends BaseConcurrentOperation<BatchResult<O>>)
        └── (future) ParallelOperation           (future — extends BaseConcurrentOperation<BatchResult<Object>>)
```

### Call Flow

1. User calls `ctx.map("process-orders", orders, OrderResult.class, (ctx, order, i) -> processOrder(ctx, order))`
2. `DurableContext.map()` validates inputs:
   - `items` not null → `IllegalArgumentException`
   - `function` not null → `IllegalArgumentException`
   - `name` valid → `ParameterValidator.validateOperationName(name)`
   - Rejects collections without stable iteration order (e.g., `HashSet`) → `IllegalArgumentException`
3. Internally converts `Collection<I>` to `List<I>` via `List.copyOf(items)` for deterministic ordering
4. For empty collection: returns `BatchResult.empty()` immediately (no checkpoint overhead)
5. `DurableContext.map()` allocates an operation ID via `nextOperationId()` and creates a `MapOperation`
6. `MapOperation.execute()` (inherited from `BaseDurableOperation`) calls `start()` or `replay()`:

   **start() flow (first execution):**
   - `BaseConcurrentOperation.start()` checkpoints the MAP operation start
   - Creates a root child context for the map operation
   - `MapOperation` iterates items, calling `branchInternal()` for each:
     - `branchInternal()` creates a `ChildContextOperation` named `"map-iteration-{i}"` with `OperationSubType.MAP_ITERATION`
     - If `activeBranches < maxConcurrency`: increments `activeBranches`, executes the branch immediately
     - Otherwise: enqueues the branch for later execution
   - When a branch completes (`onChildContextComplete` callback):
     - Decrements `activeBranches`
     - Records success (increments `succeeded` AtomicInteger) or failure (increments `failed` AtomicInteger)
     - Evaluates `CompletionConfig` criteria
     - If criteria are met (failure tolerance exceeded, min successful reached): sets `CompletionReason`, stops starting new items, does NOT wait for still-running items — their results are excluded from `BatchResult`
     - Otherwise: if queue is non-empty, registers the next branch's thread BEFORE deregistering the completed branch's thread, increments `activeBranches`, starts the next queued branch
   - When all branches are done (or early termination), aggregates results into `BatchResult`

7. `MapOperation.get()` blocks until the operation completes and returns `BatchResult<O>`

### Replay Flow

On replay, when execution reaches the `map()` call:

1. `BaseDurableOperation.execute()` finds the existing MAP operation in the checkpoint log and calls `replay()`
2. If the MAP operation is SUCCEEDED:
   - If the `BatchResult` was small (< 256KB) and was checkpointed directly: deserialize and return it immediately (no child context replay needed)
   - If the `BatchResult` was large (`replayChildren=true`): reconstruct by replaying each child context:
     - For each `map-iteration-{i}`, creates a `ChildContextOperation` and calls `replay()`
     - `ChildContextOperation.replay()` returns the cached result from the checkpoint log (no re-execution for normal-sized results)
     - For large child results (`replayChildren=true`), `ChildContextOperation` re-executes the child context code to reconstruct the result from its inner checkpointed operations
     - For FAILED children, returns the cached error
   - Aggregates all child results back into `BatchResult`
3. If the MAP operation is FAILED: `markAlreadyCompleted()` — the error is returned via `get()`
4. If the MAP operation is STARTED (interrupted mid-execution):
   - Completed children: replay returns cached results
   - Incomplete children: re-execute from their last checkpoint
   - Not-yet-started children: execute fresh
5. Returns the reconstructed `BatchResult`

This follows the same pattern as `ChildContextOperation` — checkpoint the result directly when small, use `replayChildren` when large.


## Components and Interfaces

### New: `CompletionConfig`

Location: `sdk/src/main/java/software/amazon/lambda/durable/CompletionConfig.java`

```java
package software.amazon.lambda.durable;

/**
 * Controls when a concurrent operation (map or parallel) completes.
 * Provides factory methods for common completion strategies.
 */
public class CompletionConfig {
    private final Integer minSuccessful;
    private final Integer toleratedFailureCount;
    private final Double toleratedFailurePercentage;

    private CompletionConfig(Integer minSuccessful, Integer toleratedFailureCount,
                             Double toleratedFailurePercentage) {
        this.minSuccessful = minSuccessful;
        this.toleratedFailureCount = toleratedFailureCount;
        this.toleratedFailurePercentage = toleratedFailurePercentage;
    }

    /** All items must succeed. Zero failures tolerated. */
    public static CompletionConfig allSuccessful() {
        return new CompletionConfig(null, 0, null);
    }

    /** All items run regardless of failures. Failures captured per-item. */
    public static CompletionConfig allCompleted() {
        return new CompletionConfig(null, null, null);
    }

    /** Complete as soon as the first item succeeds. */
    public static CompletionConfig firstSuccessful() {
        return new CompletionConfig(1, null, null);
    }

    public Integer minSuccessful() { return minSuccessful; }
    public Integer toleratedFailureCount() { return toleratedFailureCount; }
    public Double toleratedFailurePercentage() { return toleratedFailurePercentage; }
}
```

### New: `CompletionReason` Enum

Location: `sdk/src/main/java/software/amazon/lambda/durable/model/CompletionReason.java`

```java
package software.amazon.lambda.durable.model;

/** Indicates why a concurrent operation completed. */
public enum CompletionReason {
    ALL_COMPLETED,
    MIN_SUCCESSFUL_REACHED,
    FAILURE_TOLERANCE_EXCEEDED
}
```

### New: `MapConfig`

Location: `sdk/src/main/java/software/amazon/lambda/durable/MapConfig.java`

```java
package software.amazon.lambda.durable;

/**
 * Configuration for map operations. Separate from ParallelConfig with
 * different defaults: lenient completion (all items run) and unlimited concurrency.
 */
public class MapConfig {
    private final Integer maxConcurrency;
    private final CompletionConfig completionConfig;

    private MapConfig(Builder builder) {
        this.maxConcurrency = builder.maxConcurrency;
        this.completionConfig = builder.completionConfig;
    }

    /** Max concurrent items. Null means unlimited. */
    public Integer maxConcurrency() { return maxConcurrency; }

    /** Completion criteria. Defaults to allCompleted(). */
    public CompletionConfig completionConfig() {
        return completionConfig != null ? completionConfig : CompletionConfig.allCompleted();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Integer maxConcurrency;
        private CompletionConfig completionConfig;

        public Builder maxConcurrency(Integer maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder completionConfig(CompletionConfig completionConfig) {
            this.completionConfig = completionConfig;
            return this;
        }

        public MapConfig build() { return new MapConfig(this); }
    }
}
```


### New: `MapFunction<I, O>` Functional Interface

Location: `sdk/src/main/java/software/amazon/lambda/durable/MapFunction.java`

```java
package software.amazon.lambda.durable;

/**
 * Function applied to each item in a map operation.
 *
 * @param <I> the input item type
 * @param <O> the output result type
 */
@FunctionalInterface
public interface MapFunction<I, O> {
    O apply(DurableContext context, I item, int index) throws Exception;
}
```

### New: `BaseConcurrentOperation<R>`

Location: `sdk/src/main/java/software/amazon/lambda/durable/operation/BaseConcurrentOperation.java`

Abstract class extending `BaseDurableOperation<R>` that provides the shared concurrent execution framework for both map and parallel operations. This follows the prototype's architecture where `BaseConcurrentOperation` is a proper abstract class in the operation hierarchy, not a utility.

#### Responsibilities

| Responsibility | Implementation |
|---|---|
| Root child context | Creates a root child context via `getContext().createChildContext(operationId, name)` — all branches are children of this root |
| Branch creation | `branchInternal(name, typeToken, serDes, function)` creates `ChildContextOperation` instances as children of the root context |
| Concurrency limiting | `ConcurrentLinkedQueue` of pending branches + `activeBranches` AtomicInteger counter. Starts new branches only when `activeBranches < maxConcurrency` |
| Success/failure tracking | `succeeded` and `failed` AtomicInteger counters, incremented in `onChildContextComplete` |
| Completion evaluation | Evaluates `CompletionConfig` criteria (toleratedFailureCount, toleratedFailurePercentage, minSuccessful) after each branch completes |
| Early termination | When criteria are met, sets `CompletionReason`, stops starting new branches, does NOT wait for still-running branches |
| Thread ordering | In `onChildContextComplete`: registers next branch's thread BEFORE deregistering completed branch's thread (prevents premature suspension) |
| Lifecycle | Extends `BaseDurableOperation` for standard execute/start/replay/get lifecycle |
| Callback pattern | `onChildContextComplete(ChildContextOperation<?> branch, boolean success)` — called by each branch when it finishes |

#### Key Internal Methods

```java
package software.amazon.lambda.durable.operation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.CompletionConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

public abstract class BaseConcurrentOperation<R> extends BaseDurableOperation<R> {

    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    private final List<ChildContextOperation<?>> branches = new ArrayList<>();
    private final Queue<ChildContextOperation<?>> pendingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeBranches = new AtomicInteger(0);
    private final AtomicInteger succeeded = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final Integer maxConcurrency;
    private final CompletionConfig completionConfig;
    private final OperationSubType subType;
    private volatile CompletionReason completionReason;
    private volatile boolean earlyTermination = false;
    private DurableContext rootContext;

    protected BaseConcurrentOperation(
            String operationId,
            String name,
            OperationSubType subType,
            Integer maxConcurrency,
            CompletionConfig completionConfig,
            TypeToken<R> resultTypeToken,
            SerDes resultSerDes,
            DurableContext durableContext) {
        super(operationId, name, OperationType.CONTEXT, resultTypeToken, resultSerDes, durableContext);
        this.subType = subType;
        this.maxConcurrency = maxConcurrency;
        this.completionConfig = completionConfig;
    }

    /** Creates a root child context and checkpoints the operation start. */
    @Override
    protected void start() {
        sendOperationUpdateAsync(
            OperationUpdate.builder()
                .action(OperationAction.START)
                .subType(subType.getValue()));
        this.rootContext = getContext().createChildContext(getOperationId(), getName());
        startBranches();
    }

    /** Subclasses implement this to call branchInternal() for each branch. */
    protected abstract void startBranches();

    /** Subclasses implement this to aggregate branch results into R. */
    protected abstract R aggregateResults();

    /**
     * Creates a ChildContextOperation as a child of the root context and
     * either starts it immediately or enqueues it.
     */
    protected <T> ChildContextOperation<T> branchInternal(
            String branchName,
            TypeToken<T> typeToken,
            SerDes serDes,
            Function<DurableContext, T> function) {
        var branchOpId = rootContext.nextOperationId();
        var branch = new ChildContextOperation<>(
            branchOpId, branchName, function,
            OperationSubType.MAP_ITERATION, typeToken, serDes, rootContext);
        branches.add(branch);

        if (maxConcurrency == null || activeBranches.get() < maxConcurrency) {
            activeBranches.incrementAndGet();
            branch.execute();
        } else {
            pendingQueue.add(branch);
        }
        return branch;
    }

    /**
     * Called when a child context completes. Handles:
     * 1. Updating success/failure counters
     * 2. Evaluating CompletionConfig criteria
     * 3. Starting next queued branch with correct thread ordering
     */
    protected void onChildContextComplete(ChildContextOperation<?> branch, boolean success) {
        if (success) {
            succeeded.incrementAndGet();
        } else {
            failed.incrementAndGet();
        }

        // Evaluate completion criteria
        if (shouldTerminateEarly()) {
            earlyTermination = true;
            activeBranches.decrementAndGet();
            // Do NOT wait for still-running branches
            if (activeBranches.get() == 0) {
                finalizeOperation();
            }
            return;
        }

        // Start next queued branch with correct thread ordering:
        // register new branch thread BEFORE deregistering completed branch thread
        var next = pendingQueue.poll();
        if (next != null) {
            // activeBranches stays the same (one out, one in)
            next.execute();  // registers new thread internally
        } else {
            activeBranches.decrementAndGet();
        }
        // completed branch thread deregistered by ChildContextOperation

        if (activeBranches.get() == 0 && pendingQueue.isEmpty()) {
            finalizeOperation();
        }
    }

    private boolean shouldTerminateEarly() {
        int totalCompleted = succeeded.get() + failed.get();

        // Check minSuccessful
        if (completionConfig.minSuccessful() != null
                && succeeded.get() >= completionConfig.minSuccessful()) {
            completionReason = CompletionReason.MIN_SUCCESSFUL_REACHED;
            return true;
        }

        // Check toleratedFailureCount
        if (completionConfig.toleratedFailureCount() != null
                && failed.get() > completionConfig.toleratedFailureCount()) {
            completionReason = CompletionReason.FAILURE_TOLERANCE_EXCEEDED;
            return true;
        }

        // Check toleratedFailurePercentage
        if (completionConfig.toleratedFailurePercentage() != null
                && totalCompleted > 0
                && ((double) failed.get() / totalCompleted)
                    > completionConfig.toleratedFailurePercentage()) {
            completionReason = CompletionReason.FAILURE_TOLERANCE_EXCEEDED;
            return true;
        }

        return false;
    }

    private void finalizeOperation() {
        if (completionReason == null) {
            completionReason = CompletionReason.ALL_COMPLETED;
        }
        // Checkpoint and complete — subclass aggregateResults() builds the final result
        // Checkpointing logic (small vs large) handled here
    }

    // Accessors for subclasses
    protected List<ChildContextOperation<?>> getBranches() { return branches; }
    protected CompletionReason getCompletionReason() { return completionReason; }
    protected AtomicInteger getSucceeded() { return succeeded; }
    protected AtomicInteger getFailed() { return failed; }
    protected boolean isEarlyTermination() { return earlyTermination; }
    protected DurableContext getRootContext() { return rootContext; }
}
```

#### Checkpoint Strategy (Small vs Large Results)

`BaseConcurrentOperation` uses the same threshold as `ChildContextOperation` (256KB):

- **Small result (< 256KB):** Serialize the aggregated result (e.g., `BatchResult`) and checkpoint it directly as the operation's payload. On replay, deserialize and return — no child replay needed.
- **Large result (≥ 256KB):** Checkpoint with empty payload and `replayChildren=true`. On replay, re-create all branches and replay each `ChildContextOperation` to reconstruct the result from child checkpoints.

This is identical to how `ChildContextOperation` handles its own large results.


### New: `MapOperation<I, O>`

Location: `sdk/src/main/java/software/amazon/lambda/durable/operation/MapOperation.java`

Extends `BaseConcurrentOperation<BatchResult<O>>`. Orchestrates N `ChildContextOperation` instances, one per item. Aggregates results into `BatchResult`.

```java
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.MapConfig;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.BatchResult;
import software.amazon.lambda.durable.model.OperationSubType;

public class MapOperation<I, O> extends BaseConcurrentOperation<BatchResult<O>> {
    private final List<I> items;
    private final MapFunction<I, O> function;
    private final TypeToken<O> itemResultTypeToken;

    public MapOperation(String operationId, String name, List<I> items,
                        MapFunction<I, O> function, MapConfig config,
                        TypeToken<O> itemResultTypeToken,
                        DurableContext durableContext) {
        super(operationId, name, OperationSubType.MAP,
              config.maxConcurrency(), config.completionConfig(),
              new TypeToken<BatchResult<O>>() {},
              durableContext.getDurableConfig().getSerDes(),
              durableContext);
        this.items = items;
        this.function = function;
        this.itemResultTypeToken = itemResultTypeToken;
    }

    @Override
    protected void startBranches() {
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            branchInternal(
                "map-iteration-" + i,
                itemResultTypeToken,
                getContext().getDurableConfig().getSerDes(),
                childCtx -> {
                    try {
                        return function.apply(childCtx, items.get(index), index);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            );
        }
    }

    @Override
    protected BatchResult<O> aggregateResults() {
        var results = new ArrayList<O>(Collections.nCopies(items.size(), null));
        var errors = new ArrayList<Throwable>(Collections.nCopies(items.size(), null));

        for (int i = 0; i < getBranches().size(); i++) {
            var branch = getBranches().get(i);
            try {
                @SuppressWarnings("unchecked")
                var result = (O) branch.get();
                results.set(i, result);
            } catch (Exception e) {
                errors.set(i, e);
            }
        }

        return new BatchResult<>(results, errors, getCompletionReason());
    }

    @Override
    public BatchResult<O> get() {
        var op = waitForOperationCompletion();
        // ... handle SUCCEEDED (small vs large), FAILED, STARTED
        return aggregateResults();
    }
}
```

Key implementation details:
- Operation ID is allocated in `DurableContext.map()` via `nextOperationId()` and passed to `MapOperation`
- Each item's `MapFunction` is wrapped as `Function<DurableContext, O>` for `ChildContextOperation`: `childCtx -> function.apply(childCtx, items.get(i), i)`
- The `MapFunction.apply()` declares `throws Exception` but `ChildContextOperation` expects `Function<DurableContext, T>` which doesn't declare checked exceptions — the wrapper catches and re-throws checked exceptions as `RuntimeException`
- `ChildContextOperation` is created with `OperationSubType.MAP_ITERATION` via `branchInternal()`
- Threading is handled by `ChildContextOperation` which uses `DurableConfig.getExecutorService()`
- Suspend/resume is handled by `ExecutionManager` (not our concern)
- On early termination (completion criteria met), still-running items are NOT waited for — their results are excluded from `BatchResult`
- On replay with `replayChildren=true`, `MapOperation` re-creates all branches via `startBranches()` and each `ChildContextOperation.replay()` returns cached results

### New: `OperationSubType` Addition

The existing `OperationSubType` enum gets one new value:

```java
MAP_ITERATION("MapIteration");
```

The existing `MAP("Map")` value is already present and is used for the top-level `BaseConcurrentOperation` checkpoint. `MAP_ITERATION` is used for each per-item `ChildContextOperation`.

Note: `PARALLEL_BRANCH` will be added when the parallel operation is implemented.

### Call Flow

1. User calls `ctx.map("process-orders", orders, OrderResult.class, (ctx, order, i) -> processOrder(ctx, order))`
2. `DurableContext.map()` validates inputs (null checks on collection and function, rejects known unordered collections)
3. Creates the operation ID via `nextOperationId()`
4. Internally converts `Collection<I>` to `List<I>` via `List.copyOf(items)` for deterministic ordering
5. For empty collection: returns `BatchResult.empty()` immediately (no checkpoint overhead)
6. Creates a `MapOperation` with the operationId, items list, function, and `MapConfig`
7. `MapOperation.execute()` (non-blocking):
   - Checkpoints the MAP operation start via `BaseConcurrentOperation`
   - Creates a root child context for the map operation
   - For each item at index `i`:
     - Creates a `ChildContextOperation` named `"map-iteration-{i}"` with `OperationSubType.MAP_ITERATION`
     - Adds it to the queue
     - If `activeBranches < maxConcurrency`, starts execution immediately; otherwise stays queued
   - `ChildContextOperation` runs the `MapFunction` in a thread from `DurableConfig.getExecutorService()`
   - On completion, `onChildContextComplete` callback:
     - Decrements `activeBranches`
     - Records success or failure
     - Evaluates `CompletionConfig` criteria
     - If not done: registers next branch's thread, then starts next queued branch (thread ordering)
     - If done: checkpoints the MAP operation as SUCCEEDED with the `BatchResult` payload (if small) or empty payload with `replayChildren=true` (if large)
8. `map()` calls `operation.get()` which blocks until the MAP operation completes
9. Returns `BatchResult<O>` with results, errors, and `CompletionReason`

### Replay Flow

On replay, when execution reaches the `map()` call:

1. `MapOperation` checks the checkpoint log for the top-level MAP operation
2. If the MAP operation is SUCCEEDED with a stored `BatchResult` (small result):
   - Returns the deserialized `BatchResult` directly — no child context replay needed
3. If the MAP operation is SUCCEEDED with `replayChildren=true` (large result):
   - Reconstructs the `BatchResult` by replaying each child context:
     - For each `map-iteration-{i}`, creates a `ChildContextOperation` and calls `replay()`
     - `ChildContextOperation.replay()` returns the cached result from the checkpoint log (no re-execution for normal-sized results)
     - For large child results (`replayChildren=true`), `ChildContextOperation` re-executes the child context code to reconstruct the result from its inner checkpointed operations
     - For FAILED children, returns the cached error
   - Aggregates all child results back into `BatchResult`
4. If the MAP operation is STARTED (interrupted mid-execution):
   - Completed children: replay returns cached results
   - Incomplete children: re-execute from their last checkpoint
   - Not-yet-started children: execute fresh
5. Returns the reconstructed `BatchResult`

### Early Termination

When `CompletionConfig` criteria are met (failure tolerance exceeded, min successful reached):
- `MapOperation` stops starting new queued items
- Already-running items are NOT waited for — their results are not included in the `BatchResult`
- The `BatchResult` includes results from completed items only, with appropriate `CompletionReason`


## Components and Interfaces

### New: `CompletionConfig`

Location: `sdk/src/main/java/software/amazon/lambda/durable/CompletionConfig.java`

```java
package software.amazon.lambda.durable;

/**
 * Controls when a concurrent operation (map or parallel) completes.
 * Provides factory methods for common completion strategies.
 */
public class CompletionConfig {
    private final Integer minSuccessful;
    private final Integer toleratedFailureCount;
    private final Double toleratedFailurePercentage;

    private CompletionConfig(Integer minSuccessful, Integer toleratedFailureCount,
                             Double toleratedFailurePercentage) {
        this.minSuccessful = minSuccessful;
        this.toleratedFailureCount = toleratedFailureCount;
        this.toleratedFailurePercentage = toleratedFailurePercentage;
    }

    /** All items must succeed. Zero failures tolerated. */
    public static CompletionConfig allSuccessful() {
        return new CompletionConfig(null, 0, null);
    }

    /** All items run regardless of failures. Failures captured per-item. */
    public static CompletionConfig allCompleted() {
        return new CompletionConfig(null, null, null);
    }

    /** Complete as soon as the first item succeeds. */
    public static CompletionConfig firstSuccessful() {
        return new CompletionConfig(1, null, null);
    }

    public Integer minSuccessful() { return minSuccessful; }
    public Integer toleratedFailureCount() { return toleratedFailureCount; }
    public Double toleratedFailurePercentage() { return toleratedFailurePercentage; }
}
```

### New: `CompletionReason` Enum

Location: `sdk/src/main/java/software/amazon/lambda/durable/model/CompletionReason.java`

```java
package software.amazon.lambda.durable.model;

/** Indicates why a concurrent operation completed. */
public enum CompletionReason {
    ALL_COMPLETED,
    MIN_SUCCESSFUL_REACHED,
    FAILURE_TOLERANCE_EXCEEDED
}
```

### New: `MapConfig`

Location: `sdk/src/main/java/software/amazon/lambda/durable/MapConfig.java`

```java
package software.amazon.lambda.durable;

/**
 * Configuration for map operations. Separate from ParallelConfig with
 * different defaults: lenient completion (all items run) and unlimited concurrency.
 */
public class MapConfig {
    private final Integer maxConcurrency;
    private final CompletionConfig completionConfig;

    private MapConfig(Builder builder) {
        this.maxConcurrency = builder.maxConcurrency;
        this.completionConfig = builder.completionConfig;
    }

    /** Max concurrent items. Null means unlimited. */
    public Integer maxConcurrency() { return maxConcurrency; }

    /** Completion criteria. Defaults to allCompleted(). */
    public CompletionConfig completionConfig() {
        return completionConfig != null ? completionConfig : CompletionConfig.allCompleted();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Integer maxConcurrency;
        private CompletionConfig completionConfig;

        public Builder maxConcurrency(Integer maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder completionConfig(CompletionConfig completionConfig) {
            this.completionConfig = completionConfig;
            return this;
        }

        public MapConfig build() { return new MapConfig(this); }
    }
}
```

### New: `MapFunction<I, O>` Functional Interface

Location: `sdk/src/main/java/software/amazon/lambda/durable/MapFunction.java`

```java
package software.amazon.lambda.durable;

/**
 * Function applied to each item in a map operation.
 *
 * @param <I> the input item type
 * @param <O> the output result type
 */
@FunctionalInterface
public interface MapFunction<I, O> {
    O apply(DurableContext context, I item, int index) throws Exception;
}
```


### Modified: `DurableContext` — New `map` and `mapAsync` Methods

New methods added to `DurableContext`. The public API accepts `Collection<I>` and converts internally to `List<I>` via `List.copyOf(items)`. Collections without stable iteration order (e.g., `HashSet`, `HashMap.values()`) are rejected at runtime with an `IllegalArgumentException`.

**API warning (Javadoc):** The `items` parameter must be a collection with a stable, deterministic iteration order (e.g., `List`, `LinkedHashSet`). Collections without stable ordering (e.g., `HashSet`) will throw `IllegalArgumentException` at runtime because checkpoint-and-replay correctness requires items to be processed in the same order across invocations.

```java
// ========== map methods (4 overloads, name always required) ==========

// Full signature with name, result type (Class), and config
public <I, O> BatchResult<O> map(String name, Collection<I> items, Class<O> resultType,
                                  MapFunction<I, O> function, MapConfig config)

// Without config (uses MapConfig defaults: unlimited concurrency, allCompleted)
public <I, O> BatchResult<O> map(String name, Collection<I> items, Class<O> resultType,
                                  MapFunction<I, O> function)

// TypeToken variants for generic result types
public <I, O> BatchResult<O> map(String name, Collection<I> items, TypeToken<O> resultType,
                                  MapFunction<I, O> function, MapConfig config)

public <I, O> BatchResult<O> map(String name, Collection<I> items, TypeToken<O> resultType,
                                  MapFunction<I, O> function)

// ========== mapAsync methods (4 overloads, name always required) ==========

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    Class<O> resultType, MapFunction<I, O> function, MapConfig config)

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    Class<O> resultType, MapFunction<I, O> function)

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config)

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    TypeToken<O> resultType, MapFunction<I, O> function)
```

Note: Consistent with all other `DurableContext` operations (`step`, `wait`, `invoke`, `createCallback`, `runInChildContext`), `name` is always required as the first parameter. There are no overloads that omit the name.

Core implementation sketch:

```java
public <I, O> BatchResult<O> map(String name, Collection<I> items, Class<O> resultType,
                                  MapFunction<I, O> function, MapConfig config) {
    return mapAsync(name, items, TypeToken.get(resultType), function, config).get();
}

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
        TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config) {
    Objects.requireNonNull(items, "items cannot be null");
    Objects.requireNonNull(function, "function cannot be null");
    Objects.requireNonNull(resultType, "resultType cannot be null");
    ParameterValidator.validateOperationName(name);
    validateStableIterationOrder(items);

    var itemList = List.copyOf(items);  // defensive copy + deterministic ordering
    if (itemList.isEmpty()) {
        return completedFuture(BatchResult.empty());
    }

    var effectiveConfig = config != null ? config : MapConfig.builder().build();
    var operationId = nextOperationId();
    var operation = new MapOperation<>(operationId, name, itemList, function,
        effectiveConfig, resultType, this);
    operation.execute();
    return operation;
}

/**
 * Validates that the collection has a stable iteration order.
 * Rejects HashSet, HashMap.values(), etc.
 */
private static <I> void validateStableIterationOrder(Collection<I> items) {
    if (items instanceof java.util.HashSet
            || items instanceof java.util.HashMap.values().getClass()) {
        throw new IllegalArgumentException(
            "items must have a stable iteration order (e.g., List, LinkedHashSet). "
            + "HashSet and similar unordered collections are not supported because "
            + "checkpoint-and-replay requires deterministic item ordering.");
    }
}
```

The `validateStableIterationOrder` method uses `instanceof` checks against known unordered collection types. This is a best-effort runtime check — it cannot catch all possible unordered collections (e.g., custom implementations), but it catches the most common mistakes. The Javadoc warning serves as the primary defense.

### New: `BaseConcurrentOperation<R>`

Location: `sdk/src/main/java/software/amazon/lambda/durable/operation/BaseConcurrentOperation.java`

Abstract class extending `BaseDurableOperation<R>` that provides the shared concurrent execution framework for map and parallel operations.

```java
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.lambda.durable.ConcurrencyConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.model.OperationSubType;

public abstract class BaseConcurrentOperation<R> extends BaseDurableOperation<R> {

    private final ArrayList<ChildContextOperation<?>> branches;
    private final Queue<ChildContextOperation<?>> queue;
    private final DurableContext rootContext;
    private final AtomicInteger succeeded;
    private final AtomicInteger failed;
    private final OperationSubType subType;
    private final ConcurrencyConfig config;
    private final AtomicInteger activeBranches;

    // Creates root child context, initializes queue and counters
    // branchInternal() — creates a ChildContextOperation, adds to queue, starts if concurrency allows
    // executeNewBranchIfConcurrencyAllows() — starts next queued branch if under maxConcurrency
    // onChildContextComplete() — decrements activeBranches, records success/failure,
    //   evaluates completion, starts next branch (with correct thread registration ordering)
    // isDone() — checks if minSuccessful reached or toleratedFailureCount exceeded
}
```

Key behaviors:
- `branchInternal()` creates a `ChildContextOperation` as a child of `rootContext` and queues it
- `executeNewBranchIfConcurrencyAllows()` checks `activeBranches < maxConcurrency` before starting
- `onChildContextComplete()` is called by `ChildContextOperation` when done — it must register the next branch's thread before the current branch's thread is deregistered
- When `isDone()` returns true, checkpoints the operation as SUCCEEDED

### New: `MapOperation<I, O>`

Location: `sdk/src/main/java/software/amazon/lambda/durable/operation/MapOperation.java`

Extends `BaseConcurrentOperation` with map-specific logic.

```java
package software.amazon.lambda.durable.operation;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.MapConfig;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.model.BatchResult;
import software.amazon.lambda.durable.model.OperationSubType;

public class MapOperation<I, O> extends BaseConcurrentOperation<BatchResult<O>> {
    private final List<I> items;
    private final MapFunction<I, O> function;

    // Constructor receives operationId (created by DurableContext.map()),
    // name, items (already List.copyOf'd), function, config, durableContext

    // start(): for each item at index i, calls branchInternal() with:
    //   - name: "map-iteration-{i}"
    //   - OperationSubType.MAP_ITERATION
    //   - function wrapper: childCtx -> function.apply(childCtx, items.get(i), i)

    // get(): aggregates all branch results into BatchResult<O>
    //   maintaining input order, with CompletionReason from isDone()
}
```

### New: `OperationSubType` Addition

The existing `OperationSubType` enum gets one new value:

```java
MAP_ITERATION("MapIteration");
```

Note: `PARALLEL_BRANCH` will be added when the parallel operation is implemented.

### Modified: `DurableContext` — New `map` and `mapAsync` Methods

New methods added to `DurableContext`. The public API accepts `Collection<I>` with runtime validation.

```java
// ========== map methods ==========

// Full signature with name, result type (Class), and config
public <I, O> BatchResult<O> map(String name, Collection<I> items, Class<O> resultType,
                                  MapFunction<I, O> function, MapConfig config)

// Without config (uses MapConfig defaults: unlimited concurrency, allCompleted)
public <I, O> BatchResult<O> map(String name, Collection<I> items, Class<O> resultType,
                                  MapFunction<I, O> function)

// TypeToken variants for generic result types
public <I, O> BatchResult<O> map(String name, Collection<I> items, TypeToken<O> resultType,
                                  MapFunction<I, O> function, MapConfig config)

public <I, O> BatchResult<O> map(String name, Collection<I> items, TypeToken<O> resultType,
                                  MapFunction<I, O> function)

// ========== mapAsync methods ==========

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    Class<O> resultType, MapFunction<I, O> function, MapConfig config)

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    Class<O> resultType, MapFunction<I, O> function)

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config)

public <I, O> DurableFuture<BatchResult<O>> mapAsync(String name, Collection<I> items,
    TypeToken<O> resultType, MapFunction<I, O> function)
```

Note: Consistent with all other `DurableContext` operations (`step`, `wait`, `invoke`, `createCallback`, `runInChildContext`), `name` is always required as the first parameter.

Core implementation sketch:

```java
public <I, O> BatchResult<O> map(String name, Collection<I> items, Class<O> resultType,
                                  MapFunction<I, O> function, MapConfig config) {
    Objects.requireNonNull(items, "items cannot be null");
    Objects.requireNonNull(function, "function cannot be null");
    ParameterValidator.validateOperationName(name);
    ParameterValidator.validateOrderedCollection(items);  // rejects HashSet etc.
    var itemList = List.copyOf(items);  // defensive copy + deterministic ordering
    if (itemList.isEmpty()) {
        return BatchResult.empty();
    }
    var operationId = nextOperationId();
    var operation = new MapOperation<>(operationId, name, itemList, function,
        config != null ? config : MapConfig.builder().build(), this);
    operation.execute();
    return operation.get();
}
```

### Modified: `BatchResult<T>` Enhancements

The existing `BatchResult` class gains new fields and methods:

```java
// New field
private final CompletionReason completionReason;

// New accessor methods
public CompletionReason completionReason() { return completionReason; }
public ExecutionStatus status() {
    return failureCount() == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
}
public int successCount() { /* count non-null results with null errors */ }
public int failureCount() { /* count non-null errors */ }
public int startedCount() { /* count items that were started */ }
public int totalCount() { /* total items including not-started */ }
public List<T> succeeded() { /* filter to successful results */ }
public List<Throwable> failed() { /* filter to failed errors */ }

// New static factory
public static <T> BatchResult<T> empty() {
    return new BatchResult<>(List.of(), List.of(), CompletionReason.ALL_COMPLETED);
}
```

### Reused Types (No Modifications)

| Type | Package | Role |
|------|---------|------|
| `DurableFuture<T>` | `software.amazon.lambda.durable` | Async handle for `mapAsync` |
| `TypeToken<T>` | `software.amazon.lambda.durable` | Generic result type for deserialization |
| `ChildContextOperation<T>` | `software.amazon.lambda.durable.operation` | Per-item child context execution, threading, checkpointing, replay, suspend/resume |
| `ExecutionManager` | `software.amazon.lambda.durable.execution` | Thread coordination, suspend/resume |
| `DurableConfig.getExecutorService()` | `software.amazon.lambda.durable` | User's thread pool for running child context code |


### Modified: `BatchResult<T>` Enhancements

The existing `BatchResult` class (or new class if it doesn't exist yet) gains new fields and methods:

```java
package software.amazon.lambda.durable.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result container for concurrent operations (map, parallel).
 * Maintains input order: getResult(i) corresponds to the i-th input item.
 */
public class BatchResult<T> {
    private final List<T> results;
    private final List<Throwable> errors;
    private final CompletionReason completionReason;

    public BatchResult(List<T> results, List<Throwable> errors, CompletionReason completionReason) {
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.completionReason = completionReason;
    }

    /** Result at index i, or null if that item failed or was not started. */
    public T getResult(int i) { return results.get(i); }

    /** Error at index i, or null if that item succeeded or was not started. */
    public Throwable getError(int i) { return errors.get(i); }

    /** Why the operation completed. */
    public CompletionReason completionReason() { return completionReason; }

    /** SUCCEEDED if no failures, FAILED otherwise. */
    public ExecutionStatus status() {
        return failureCount() == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
    }

    /** True iff all started items succeeded (no errors). */
    public boolean allSucceeded() { return failureCount() == 0; }

    /** Count of items that succeeded. */
    public int successCount() {
        return (int) results.stream().filter(r -> r != null).count()
             - (int) errors.stream().filter(e -> e != null).count()
             + /* adjust for null-returning successes */ 0;
        // Simplified: count indices where error is null and the item was started
    }

    /** Count of items that failed. */
    public int failureCount() {
        return (int) errors.stream().filter(e -> e != null).count();
    }

    /** Count of items that were started (succeeded + failed). */
    public int startedCount() {
        int started = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) != null || errors.get(i) != null) {
                started++;
            }
        }
        return started;
    }

    /** Total items including not-started ones. */
    public int totalCount() { return results.size(); }

    /** Filter to successful results (preserving order, skipping nulls/failures). */
    public List<T> succeeded() {
        var list = new ArrayList<T>();
        for (int i = 0; i < results.size(); i++) {
            if (errors.get(i) == null && results.get(i) != null) {
                list.add(results.get(i));
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** Filter to failed errors (preserving order, skipping successes). */
    public List<Throwable> failed() {
        var list = new ArrayList<Throwable>();
        for (var e : errors) {
            if (e != null) {
                list.add(e);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** Empty result — zero items, all succeeded, ALL_COMPLETED. */
    public static <T> BatchResult<T> empty() {
        return new BatchResult<>(List.of(), List.of(), CompletionReason.ALL_COMPLETED);
    }
}
```

### Reused Types (No Modifications)

| Type | Package | Role |
|------|---------|------|
| `DurableFuture<T>` | `software.amazon.lambda.durable` | Async handle for `mapAsync` |
| `TypeToken<T>` | `software.amazon.lambda.durable` | Generic result type for deserialization |
| `ChildContextOperation<T>` | `software.amazon.lambda.durable.operation` | Per-item child context execution, threading, checkpointing, replay, suspend/resume |
| `ExecutionManager` | `software.amazon.lambda.durable.execution` | Thread coordination, suspend/resume |
| `DurableConfig.getExecutorService()` | `software.amazon.lambda.durable` | User's thread pool for running child context code |
| `BaseDurableOperation<T>` | `software.amazon.lambda.durable.operation` | Base class providing execute/start/replay/get lifecycle |


## Data Models

### New Types

| Type | Kind | Location | Notes |
|------|------|----------|-------|
| `MapFunction<I, O>` | `@FunctionalInterface` | `software.amazon.lambda.durable` | `O apply(DurableContext ctx, I item, int index) throws Exception` |
| `CompletionConfig` | Class | `software.amazon.lambda.durable` | Factory methods: `allSuccessful()`, `allCompleted()`, `firstSuccessful()` |
| `CompletionReason` | Enum | `software.amazon.lambda.durable.model` | `ALL_COMPLETED`, `MIN_SUCCESSFUL_REACHED`, `FAILURE_TOLERANCE_EXCEEDED` |
| `MapConfig` | Class (Builder) | `software.amazon.lambda.durable` | `maxConcurrency` (Integer, nullable), `completionConfig` (defaults to `allCompleted()`) |
| `BaseConcurrentOperation<R>` | Abstract class | `software.amazon.lambda.durable.operation` | Extends `BaseDurableOperation<R>`, shared concurrent execution framework |
| `MapOperation<I, O>` | Class | `software.amazon.lambda.durable.operation` | Extends `BaseConcurrentOperation<BatchResult<O>>`, aggregates into BatchResult |
| `BatchResult<T>` | Class | `software.amazon.lambda.durable.model` | Result container with ordered results, errors, completionReason, status, filtered accessors |

### Modified Types

| Type | Change |
|------|--------|
| `OperationSubType` | Add `MAP_ITERATION("MapIteration")` |
| `DurableContext` | Add 4 `map` + 4 `mapAsync` methods, add `validateStableIterationOrder()` |

### Branch Naming Convention

Each item at index `i` produces a child context named `"map-iteration-{i}"` (e.g., `"map-iteration-0"`, `"map-iteration-1"`). This naming:
- Provides meaningful names in checkpoint data and logs
- Is deterministic across replays (critical for checkpoint-and-replay correctness)
- Avoids collisions since indices are unique within a single `map()` call
- Uses the `"map-iteration-"` prefix to distinguish from parallel's future `"parallel-branch-"` prefix

### Serialization

No new serialization logic is needed. The `ChildContextOperation` infrastructure already handles serializing/deserializing child context results via `SerDes`. The `Class<O>` or `TypeToken<O>` parameter flows through to the child context operation for deserialization.

### Checkpoint Structure

The map operation produces the following checkpoint hierarchy:

```
CONTEXT (MAP) — operationId from DurableContext.nextOperationId()
  ├── CONTEXT (MAP_ITERATION) — "map-iteration-0" — child of root context
  │     └── (inner operations from MapFunction: steps, waits, etc.)
  ├── CONTEXT (MAP_ITERATION) — "map-iteration-1" — child of root context
  │     └── (inner operations from MapFunction)
  └── ... N iterations
```

The top-level MAP context is checkpointed by `BaseConcurrentOperation`. Each MAP_ITERATION is checkpointed by `ChildContextOperation`. Inner operations within each iteration are checkpointed by their respective operation classes.


## Data Models

### New Types

| Type | Kind | Location | Notes |
|------|------|----------|-------|
| `MapFunction<I, O>` | `@FunctionalInterface` | `software.amazon.lambda.durable` | `O apply(DurableContext ctx, I item, int index) throws Exception` |
| `CompletionConfig` | Class | `software.amazon.lambda.durable` | Factory methods: `allSuccessful()`, `allCompleted()`, `firstSuccessful()` |
| `CompletionReason` | Enum | `software.amazon.lambda.durable.model` | `ALL_COMPLETED`, `MIN_SUCCESSFUL_REACHED`, `FAILURE_TOLERANCE_EXCEEDED` |
| `MapConfig` | Class (Builder) | `software.amazon.lambda.durable` | `maxConcurrency` (Integer, nullable), `completionConfig` (defaults to `allCompleted()`) |
| `BaseConcurrentOperation<R>` | Abstract class | `software.amazon.lambda.durable.operation` | Shared base for map and parallel: root context, queue, concurrency, completion |
| `MapOperation<I, O>` | Class | `software.amazon.lambda.durable.operation` | Extends `BaseConcurrentOperation`, map-specific logic, aggregates into `BatchResult` |

### Modified Types

| Type | Change |
|------|--------|
| `BatchResult<T>` | Add `completionReason` field, `status()`, `successCount()`, `failureCount()`, `succeeded()`, `failed()`, `empty()` |
| `OperationSubType` | Add `MAP_ITERATION("MapIteration")` |

### Branch Naming Convention

Each item at index `i` produces a child context named `"map-iteration-{i}"` (e.g., `"map-iteration-0"`, `"map-iteration-1"`). This naming:
- Provides meaningful names in checkpoint data and logs
- Is deterministic across replays (critical for checkpoint-and-replay correctness)
- Avoids collisions since indices are unique within a single `map()` call
- Uses the `"map-iteration-"` prefix to distinguish from parallel's future `"parallel-branch-"` prefix

### Serialization

No new serialization logic is needed. The `ChildContextOperation` infrastructure already handles serializing/deserializing child context results via `SerDes`. The `Class<O>` or `TypeToken<O>` parameter flows through to the child context operation for deserialization.

### Collection Ordering Validation

The public API accepts `Collection<I>` but requires deterministic iteration order for replay correctness. At runtime, `ParameterValidator.validateOrderedCollection()` rejects known unordered types:
- `HashSet` (and subclasses)
- `HashMap.values()`, `HashMap.keySet()`, `HashMap.entrySet()`

Accepted ordered collection types include: `List`, `LinkedHashSet`, `TreeSet`, `ArrayDeque`, `LinkedHashMap.values()`.

The Javadoc for `map()` and `mapAsync()` clearly documents: "The collection must have deterministic iteration order. Unordered collections like HashSet will be rejected. Use List, LinkedHashSet, or TreeSet."


## Correctness Properties

### Property 1: Items-to-function bijection

*For any* non-empty collection of N items and any `MapFunction`, calling `map()` shall create exactly N `ChildContextOperation` instances and pass each item to the function exactly once, such that the set of (item, index) pairs received by the function equals the set of (items[i], i) pairs from the input.

**Validates: Requirements 3.1, 3.2**

### Property 2: Result ordering preservation

*For any* collection of items and a deterministic `MapFunction` that may succeed or fail per item, the returned `BatchResult` shall satisfy: for all `0 <= i < N`, `getResult(i)` equals the function's return value for item `i` when it succeeds, and `getError(i)` is non-null with the thrown exception when item `i` fails.

**Validates: Requirements 5.1, 5.2, 6.2**

### Property 3: allSucceeded consistency

*For any* `BatchResult` returned by `map()`, `allSucceeded()` shall return `true` if and only if `getError(i)` is `null` for every index `i`. Equivalently, `failureCount() == 0` iff `allSucceeded()`.

**Validates: Requirements 5.3, 5.4**

### Property 4: Error isolation completeness

*For any* collection of items where a random subset of items throw exceptions, all non-failing items shall still produce their expected results in the `BatchResult`, and `successCount() + failureCount()` shall equal the input collection size.

**Validates: Requirements 6.1, 6.3**

### Property 5: Concurrency limiting

*For any* collection of items and any `MapConfig` with `maxConcurrency` set to a positive integer M, the number of concurrently executing `MapFunction` invocations shall never exceed M at any point during execution.

**Validates: Requirements 4.2, 4.3**

### Property 6: Replay round-trip

*For any* valid input collection and deterministic `MapFunction`, executing `map()` and then replaying the execution from checkpointed state shall produce a `BatchResult` equivalent to the original — same results at the same indices, with no re-execution of previously completed items.

**Validates: Requirements 3.4, 8.1, 8.2, 8.3**

### Property 7: Failure tolerance completion

*For any* collection of items and a `CompletionConfig` with `toleratedFailureCount` set to F, if more than F items fail, the `BatchResult` shall have `completionReason` equal to `FAILURE_TOLERANCE_EXCEEDED`.

**Validates: Requirements 11.4**

### Property 8: Min successful completion

*For any* collection of items and a `CompletionConfig` with `minSuccessful` set to S, if at least S items succeed, the `BatchResult` shall have `completionReason` equal to `MIN_SUCCESSFUL_REACHED` and `successCount()` shall be greater than or equal to S.

**Validates: Requirements 11.5**


## Error Handling

### Input Validation Errors

| Condition | Exception | When |
|-----------|-----------|------|
| `items` is `null` | `IllegalArgumentException("items cannot be null")` | Before any operation ID is allocated |
| `function` is `null` | `IllegalArgumentException("function cannot be null")` | Before any operation ID is allocated |
| `items` is unordered (e.g., `HashSet`) | `IllegalArgumentException("items must have deterministic iteration order")` | Before any operation ID is allocated |
| `name` is invalid (empty, too long, non-ASCII) | `IllegalArgumentException` | Via existing `ParameterValidator.validateOperationName()` |

These validations happen eagerly in the `DurableContext.map()`/`mapAsync()` methods, before creating the operation ID or `MapOperation`. This ensures no operation IDs are consumed and no checkpoints are created for invalid calls.

### Per-Item Errors

Per-item error handling is managed by `MapOperation` via `ChildContextOperation`:

- If a `MapFunction` throws any exception (checked or unchecked), `ChildContextOperation` catches it and checkpoints the failure. `MapOperation` records it in the `BatchResult` at the corresponding index.
- Other items continue executing (unless `CompletionConfig` criteria are exceeded).
- The `BatchResult` provides `allSucceeded()`, `failureCount()`, and `failed()` for inspecting errors.

### Early Termination

When `CompletionConfig` criteria are exceeded:
- `toleratedFailureCount` exceeded: `MapOperation` stops starting new items, sets `CompletionReason.FAILURE_TOLERANCE_EXCEEDED`
- `toleratedFailurePercentage` exceeded: same behavior
- `minSuccessful` reached: `MapOperation` stops starting new items, sets `CompletionReason.MIN_SUCCESSFUL_REACHED`
- Already-running items are NOT waited for — their results are not included in the `BatchResult`

### Empty Collection Handling

An empty input collection is not an error. `map()` returns `BatchResult.empty()` immediately — a `BatchResult` with zero results, zero errors, `allSucceeded() == true`, and `CompletionReason.ALL_COMPLETED`. This avoids unnecessary checkpoint overhead.

### Null Items Within the Collection

Individual null items within the collection are not validated by `map()` itself. If a user passes a collection containing null elements, the `MapFunction` will receive `null` as the item. It is the user's responsibility to handle null items in their function, or the function will throw a `NullPointerException` which will be captured in the `BatchResult` at that index.


## Testing Strategy

### Property-Based Testing

Property-based tests use **jqwik** (https://jqwik.net/) as the PBT library for Java. jqwik integrates natively with JUnit 5 and provides powerful generators and shrinking.

Each correctness property from the design maps to a single property-based test. Tests should run a minimum of 100 iterations.

Each test must be tagged with a comment referencing the design property:
```java
// Feature: parallel-map-operation, Property 1: Items-to-function bijection
```

**Property tests to implement:**

1. **Items-to-function bijection** — Generate random lists of 1-100 items. Use a recording `MapFunction` that stores each received (item, index) pair. Verify the recorded pairs match the input list exactly.

2. **Result ordering preservation** — Generate random lists and a function that deterministically transforms each item (e.g., `String::toUpperCase`). Optionally fail at random indices. Verify `getResult(i)` and `getError(i)` correspond to the correct items.

3. **allSucceeded consistency** — Generate random `BatchResult` instances with varying success/failure patterns. Verify `allSucceeded()` is true iff `failureCount() == 0`.

4. **Error isolation completeness** — Generate random lists of 2-50 items. Pick a random subset to fail. Verify all non-failing items have correct results, failing items have errors, and `successCount() + failureCount() == items.size()`.

5. **Concurrency limiting** — Generate random lists and random `maxConcurrency` values (1-10). Use an `AtomicInteger` to track concurrent execution count. Verify the peak never exceeds `maxConcurrency`.

6. **Replay round-trip** — Generate random lists with deterministic functions. Run via `LocalDurableTestRunner`, then replay. Verify the replayed `BatchResult` equals the original and no items were re-executed.

7. **Failure tolerance completion** — Generate random lists (10-50 items) and random `toleratedFailureCount` values. Make a subset of items fail exceeding the tolerance. Verify `completionReason` is `FAILURE_TOLERANCE_EXCEEDED`.

8. **Min successful completion** — Generate random lists (10-50 items) and random `minSuccessful` values. Verify that once enough items succeed, `completionReason` is `MIN_SUCCESSFUL_REACHED` and `successCount() >= minSuccessful`.

### Unit Tests

- **Empty collection**: `map()` with empty collection returns `BatchResult.empty()`
- **Null collection**: `map()` with null collection throws `IllegalArgumentException`
- **Null function**: `map()` with null function throws `IllegalArgumentException`
- **Unordered collection**: `map()` with `HashSet` throws `IllegalArgumentException`
- **Single item**: `map()` with one item returns correct result
- **MapFunction interface**: Verify `@FunctionalInterface` annotation, lambda compatibility
- **TypeToken variant**: `map()` with `TypeToken` for generic result types deserializes correctly
- **mapAsync returns immediately**: `mapAsync()` returns a `DurableFuture` without blocking
- **mapAsync get() blocks**: Calling `get()` on the future returns the `BatchResult`
- **CompletionConfig factory methods**: Verify `allSuccessful()`, `allCompleted()`, `firstSuccessful()` produce correct field values
- **MapConfig defaults**: Verify default `maxConcurrency` is null and default `completionConfig` is `allCompleted()`
- **BatchResult.empty()**: Verify zero results, zero errors, `allSucceeded() == true`, `CompletionReason.ALL_COMPLETED`
- **BatchResult status**: Verify `status()` returns `SUCCEEDED` when no failures, `FAILED` otherwise
- **BatchResult filtered lists**: Verify `succeeded()`, `failed()` return correct subsets

### Integration Tests

Integration tests use `LocalDurableTestRunner` to verify end-to-end behavior:

- **Multi-item map with durable steps**: Each item's function calls `ctx.step()` — verify all steps checkpoint correctly
- **Map with partial failure**: Some items succeed, some fail — verify `BatchResult` contains correct mix
- **Map with concurrency limit**: 20 items with `maxConcurrency=3` — verify correct results
- **Map replay after interruption**: Run a map, simulate interruption, replay — verify no re-execution of completed items
- **Map with CompletionConfig.allSuccessful()**: One item fails — verify behavior matches strict completion
- **Map with CompletionConfig.firstSuccessful()**: Verify early termination after first success
- **Nested map**: A map function that itself calls `map()` — verify correct behavior with nested child contexts

### Test File Locations

| Test Type | Location |
|-----------|----------|
| Unit tests | `sdk/src/test/java/software/amazon/lambda/durable/DurableContextMapTest.java` |
| Property tests | `sdk/src/test/java/software/amazon/lambda/durable/MapOperationPropertyTest.java` |
| Integration tests | `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/MapIntegrationTest.java` |


## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Items-to-function bijection

*For any* non-empty collection of N items and any `MapFunction`, calling `map()` shall create exactly N `ChildContextOperation` instances and pass each item to the function exactly once, such that the set of (item, index) pairs received by the function equals the set of (items[i], i) pairs from the input.

**Validates: Requirements 3.1, 3.2**

### Property 2: Result ordering preservation

*For any* collection of items and a deterministic `MapFunction` that may succeed or fail per item, the returned `BatchResult` shall satisfy: for all `0 <= i < N`, `getResult(i)` equals the function's return value for item `i` when it succeeds, and `getError(i)` is non-null with the thrown exception when item `i` fails.

**Validates: Requirements 5.1, 5.2, 6.2**

### Property 3: allSucceeded consistency

*For any* `BatchResult` returned by `map()`, `allSucceeded()` shall return `true` if and only if `getError(i)` is `null` for every index `i`. Equivalently, `failureCount() == 0` iff `allSucceeded()`.

**Validates: Requirements 5.3, 5.4**

### Property 4: Error isolation completeness

*For any* collection of items where a random subset of items throw exceptions, all non-failing items shall still produce their expected results in the `BatchResult`, and `successCount() + failureCount()` shall equal `startedCount()`.

**Validates: Requirements 6.1, 6.3**

### Property 5: Concurrency limiting

*For any* collection of items and any `MapConfig` with `maxConcurrency` set to a positive integer M, the number of concurrently executing `MapFunction` invocations shall never exceed M at any point during execution.

**Validates: Requirements 4.2, 4.3**

### Property 6: Replay round-trip

*For any* valid input collection and deterministic `MapFunction`, executing `map()` and then replaying the execution from checkpointed state shall produce a `BatchResult` equivalent to the original — same results at the same indices, with no re-execution of previously completed items.

**Validates: Requirements 3.4, 8.1, 8.2, 8.3**

### Property 7: Failure tolerance completion

*For any* collection of items and a `CompletionConfig` with `toleratedFailureCount` set to F, if more than F items fail, the `BatchResult` shall have `completionReason` equal to `FAILURE_TOLERANCE_EXCEEDED` and `startedCount()` shall be less than or equal to `totalCount()` (early termination — not all items were started).

**Validates: Requirements 11.4**

### Property 8: Min successful completion

*For any* collection of items and a `CompletionConfig` with `minSuccessful` set to S, if at least S items succeed, the `BatchResult` shall have `completionReason` equal to `MIN_SUCCESSFUL_REACHED` and `successCount()` shall be greater than or equal to S.

**Validates: Requirements 11.5**


## Error Handling

### Input Validation Errors

| Condition | Exception | When |
|-----------|-----------|------|
| `items` is `null` | `IllegalArgumentException("items cannot be null")` | Before any operation ID is allocated |
| `function` is `null` | `IllegalArgumentException("function cannot be null")` | Before any operation ID is allocated |
| `resultType` is `null` | `IllegalArgumentException("resultType cannot be null")` | Before any operation ID is allocated |
| `name` is invalid (empty, too long, non-ASCII) | `IllegalArgumentException` | Via existing `ParameterValidator.validateOperationName()` |
| `items` has unstable iteration order (e.g., `HashSet`) | `IllegalArgumentException` | Runtime check before operation ID allocation |

These validations happen eagerly in the `map()`/`mapAsync()` methods, before creating the `MapOperation`. This ensures no operation IDs are consumed and no checkpoints are created for invalid calls.

### Per-Item Errors

Per-item error handling is managed by `ChildContextOperation` and aggregated by `MapOperation`:

- If a `MapFunction` throws any exception (checked or unchecked), `ChildContextOperation` catches it via its existing failure handling path (`handleChildContextFailure`), checkpoints the failure, and the `onChildContextComplete` callback is invoked with `success=false`.
- `MapOperation.aggregateResults()` collects the error into the `BatchResult` at the corresponding index.
- Other items continue executing (unless `CompletionConfig` criteria are exceeded).
- The `BatchResult` provides `allSucceeded()`, `failureCount()`, and `failed()` for inspecting errors.

### Completion Criteria Errors

When `CompletionConfig` criteria are exceeded (evaluated by `BaseConcurrentOperation.shouldTerminateEarly()`):
- `toleratedFailureCount` exceeded: sets `CompletionReason.FAILURE_TOLERANCE_EXCEEDED`, stops starting new items from the queue
- `toleratedFailurePercentage` exceeded: same behavior
- `minSuccessful` reached: sets `CompletionReason.MIN_SUCCESSFUL_REACHED`, stops starting new items from the queue
- Still-running items are NOT waited for — their results are excluded from `BatchResult`. The `BatchResult.startedCount()` will be less than `totalCount()` if items were never started, and results from still-running items at the time of early termination are not included.

### Empty Collection Handling

An empty input collection is not an error. `map()` returns `BatchResult.empty()` immediately — a `BatchResult` with zero results, zero errors, `allSucceeded() == true`, and `CompletionReason.ALL_COMPLETED`. This avoids unnecessary checkpoint overhead. No operation ID is allocated.

### Null Items Within the Collection

Individual null items within the collection are not validated by `map()` itself. `List.copyOf(items)` will throw `NullPointerException` if any element is null (this is standard Java behavior for `List.copyOf`). If users need to pass nullable items, they should use a wrapper type or `Optional`.

### Unordered Collection Handling

Collections without stable iteration order (e.g., `HashSet`, `HashMap.values()`) are rejected at runtime with `IllegalArgumentException`. This is a best-effort check using `instanceof` against known unordered JDK collection types. Custom unordered collections may not be caught — the Javadoc warning serves as the primary defense. The rationale is that checkpoint-and-replay correctness requires items to be processed in the same order across invocations; an unordered collection would produce different orderings on replay, causing result mismatches.


## Testing Strategy

### Property-Based Testing

Property-based tests use **jqwik** (https://jqwik.net/) as the PBT library for Java. jqwik integrates natively with JUnit 5 and provides powerful generators and shrinking.

Each correctness property from the design maps to a single property-based test. Tests should run a minimum of 100 iterations.

Each test must be tagged with a comment referencing the design property:
```java
// Feature: parallel-map-operation, Property 1: Items-to-function bijection
```

**Property tests to implement:**

1. **Items-to-function bijection** — Generate random lists of 1-100 items. Use a recording `MapFunction` that stores each received (item, index) pair in a `ConcurrentHashMap`. Verify the recorded pairs match the input list exactly: same size, same (item, index) mappings, no duplicates, no missing items.

2. **Result ordering preservation** — Generate random lists of strings and a deterministic function (e.g., `String::toUpperCase`). Optionally fail at random indices by throwing `RuntimeException`. Verify `getResult(i)` equals the expected transformed value for successful items, and `getError(i)` is non-null for failed items.

3. **allSucceeded consistency** — Generate random `BatchResult` instances with varying success/failure patterns (random mix of null and non-null errors). Verify `allSucceeded()` returns true if and only if `failureCount() == 0`.

4. **Error isolation completeness** — Generate random lists of 2-50 items. Pick a random subset of indices to fail. Use a `MapFunction` that throws for the chosen indices and returns a deterministic value for others. Verify all non-failing items have correct results, failing items have non-null errors, and `successCount() + failureCount() == startedCount()`.

5. **Concurrency limiting** — Generate random lists of 5-30 items and random `maxConcurrency` values (1-10). Use an `AtomicInteger` to track concurrent execution count (increment on entry, decrement on exit with a small sleep to create overlap). Record the peak concurrent count. Verify the peak never exceeds `maxConcurrency`.

6. **Replay round-trip** — Generate random lists of 1-20 items with deterministic functions. Run via `LocalDurableTestRunner`, then replay the execution. Verify the replayed `BatchResult` equals the original (same results at same indices) and use a counter to verify no items were re-executed during replay.

7. **Failure tolerance completion** — Generate random lists of 10-50 items and random `toleratedFailureCount` values (0 to N/2). Configure a `MapFunction` that fails for a subset exceeding the tolerance. Verify `completionReason` is `FAILURE_TOLERANCE_EXCEEDED` and `startedCount() <= totalCount()`.

8. **Min successful completion** — Generate random lists of 10-50 items and random `minSuccessful` values (1 to N/2). Configure a `MapFunction` where enough items succeed. Verify `completionReason` is `MIN_SUCCESSFUL_REACHED` and `successCount() >= minSuccessful`.

### Unit Tests

Unit tests cover specific examples, edge cases, and error conditions:

- **Empty collection**: `map()` with empty list returns `BatchResult.empty()` — zero results, zero errors, `allSucceeded() == true`, `CompletionReason.ALL_COMPLETED`
- **Null collection**: `map()` with null collection throws `IllegalArgumentException` with message "items cannot be null"
- **Null function**: `map()` with null function throws `IllegalArgumentException` with message "function cannot be null"
- **Unordered collection**: `map()` with `HashSet` throws `IllegalArgumentException` with message about stable iteration order
- **Single item**: `map()` with one item returns `BatchResult` with one result at index 0
- **MapFunction interface**: Verify `@FunctionalInterface` annotation, lambda compatibility, checked exception support
- **TypeToken variant**: `map()` with `TypeToken<List<String>>` for generic result types deserializes correctly
- **mapAsync returns immediately**: `mapAsync()` returns a `DurableFuture` without blocking the calling thread
- **mapAsync get() blocks**: Calling `get()` on the returned `DurableFuture` blocks until complete and returns the `BatchResult`
- **CompletionConfig factory methods**: Verify `allSuccessful()` returns `toleratedFailureCount=0`, `allCompleted()` returns all nulls, `firstSuccessful()` returns `minSuccessful=1`
- **MapConfig defaults**: Verify default `maxConcurrency` is null (unlimited) and default `completionConfig` is `allCompleted()`
- **MapConfig builder**: Verify builder sets `maxConcurrency` and `completionConfig` correctly
- **BatchResult.empty()**: Verify zero results, zero errors, `allSucceeded() == true`, `CompletionReason.ALL_COMPLETED`, `totalCount() == 0`
- **BatchResult status**: Verify `status()` returns `SUCCEEDED` when no failures, `FAILED` when any failure exists
- **BatchResult filtered lists**: Verify `succeeded()` returns only successful results, `failed()` returns only errors, both in order
- **BatchResult counts**: Verify `successCount()`, `failureCount()`, `startedCount()`, `totalCount()` are consistent

### Integration Tests

Integration tests use `LocalDurableTestRunner` to verify end-to-end behavior:

- **Multi-item map with durable steps**: Each item's function calls `ctx.step()` — verify all steps checkpoint correctly and results are aggregated into `BatchResult`
- **Map with partial failure**: Some items succeed, some fail — verify `BatchResult` contains correct mix of results and errors at correct indices
- **Map with concurrency limit**: 20 items with `maxConcurrency=3` — verify correct results and that no more than 3 items execute concurrently
- **Map replay after interruption**: Run a map, simulate interruption mid-execution, replay — verify completed items return cached results without re-execution and incomplete items resume
- **Map with CompletionConfig.allSuccessful()**: One item fails — verify `CompletionReason.FAILURE_TOLERANCE_EXCEEDED` and early termination
- **Map with CompletionConfig.firstSuccessful()**: Multiple items, first one succeeds — verify `CompletionReason.MIN_SUCCESSFUL_REACHED` and `successCount() >= 1`
- **Map with large results (replayChildren)**: Items return results totaling > 256KB — verify checkpoint uses `replayChildren=true` and replay reconstructs correctly
- **Nested map**: A `MapFunction` that itself calls `ctx.map()` — verify correct behavior with nested child contexts and independent checkpointing
- **Map with empty collection**: Verify no checkpoints are created and `BatchResult.empty()` is returned

### Test File Locations

| Test Type | Location |
|-----------|----------|
| Unit tests | `sdk/src/test/java/software/amazon/lambda/durable/DurableContextMapTest.java` |
| Property tests | `sdk/src/test/java/software/amazon/lambda/durable/MapOperationPropertyTest.java` |
| Integration tests | `sdk-integration-tests/src/test/java/software/amazon/lambda/durable/MapIntegrationTest.java` |
