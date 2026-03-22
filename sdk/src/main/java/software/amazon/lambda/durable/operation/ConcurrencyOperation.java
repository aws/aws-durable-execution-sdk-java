// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Abstract base class for concurrent execution of multiple child context operations.
 *
 * <p>Encapsulates shared concurrency logic: queue-based concurrency control, success/failure counting, and completion
 * checking. Both {@code ParallelOperation} and {@code MapOperation} extend this base.
 *
 * <p>Key design points:
 *
 * <ul>
 *   <li>Does NOT register its own thread — child context threads handle all suspension
 *   <li>Uses a pending queue + running counter for concurrency control
 *   <li>Completion is determined by subclass-specific logic via abstract {@code canComplete()} and
 *       {@code validateItemCount()}
 *   <li>When a child suspends, the running count is NOT decremented
 * </ul>
 *
 * @param <T> the result type of this operation
 */
public abstract class ConcurrencyOperation<T> extends SerializableDurableOperation<T> {

    private static final Logger logger = LoggerFactory.getLogger(ConcurrencyOperation.class);

    private final int maxConcurrency;
    private final Integer minSuccessful;
    private final Integer toleratedFailureCount;
    private final AtomicInteger succeededCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    protected final AtomicBoolean isJoined = new AtomicBoolean(false);
    private final Queue<ChildContextOperation<?>> pendingQueue = new ConcurrentLinkedDeque<>();
    private final List<ChildContextOperation<?>> branches = Collections.synchronizedList(new ArrayList<>());
    private final Map<ChildContextOperation<?>, Boolean> runningChildren = new ConcurrentHashMap<>();
    private final Set<String> completedOperations = Collections.synchronizedSet(new HashSet<>());
    private final OperationIdGenerator operationIdGenerator;
    private final DurableContextImpl rootContext;
    private volatile CompletableFuture<BaseDurableOperation> vacancyListener;

    private record NewBranchItem<T>(
            String name,
            Function<DurableContext, T> function,
            TypeToken<T> resultType,
            SerDes serDes,
            OperationSubType branchSubType) {}

    protected ConcurrencyOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext,
            int maxConcurrency,
            Integer minSuccessful,
            Integer toleratedFailureCount) {
        super(operationIdentifier, resultTypeToken, resultSerDes, durableContext);
        this.maxConcurrency = maxConcurrency;
        this.minSuccessful = minSuccessful;
        this.toleratedFailureCount = toleratedFailureCount;
        this.operationIdGenerator = new OperationIdGenerator(getOperationId());
        this.rootContext = durableContext.createChildContextWithoutSettingThreadContext(getOperationId(), getName());
    }

    // ========== Template methods for subclasses ==========

    /**
     * Creates a child context operation for a single item (branch or iteration).
     *
     * @param operationId the unique operation ID for this item
     * @param name the name of this item
     * @param function the user function to execute
     * @param resultType the result type token
     * @param branchSubType the sub-type of the branch operation
     * @param parentContext the parent durable context
     * @param <R> the result type of the child operation
     * @return a new ChildContextOperation
     */
    protected <R> ChildContextOperation<R> createItem(
            String operationId,
            String name,
            Function<DurableContext, R> function,
            TypeToken<R> resultType,
            SerDes serDes,
            OperationSubType branchSubType,
            DurableContextImpl parentContext) {
        return new ChildContextOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, branchSubType),
                function,
                resultType,
                RunInChildContextConfig.builder().serDes(serDes).build(),
                parentContext,
                this);
    }

    /** Called when the concurrency operation succeeds. Subclasses define checkpointing behavior. */
    protected abstract void handleSuccess(ConcurrencyCompletionStatus concurrencyCompletionStatus);

    // ========== Concurrency control ==========

    /**
     * Creates and enqueues an item without starting execution. Use {@link #executeItems()} to begin execution after all
     * items have been enqueued. This prevents early termination from blocking item creation when all items are known
     * upfront (e.g., map operations).
     */
    protected <R> ChildContextOperation<R> enqueueItem(
            String name,
            Function<DurableContext, R> function,
            TypeToken<R> resultType,
            SerDes serDes,
            OperationSubType branchSubType) {
        var operationId = this.operationIdGenerator.nextOperationId();
        var childOp = createItem(operationId, name, function, resultType, serDes, branchSubType, this.rootContext);
        branches.add(childOp);
        pendingQueue.add(childOp);
        logger.debug("Item enqueued {}", name);
        if (vacancyListener != null) {
            vacancyListener.complete(null);
        }
        return childOp;
    }

    protected void executeItems() {
        // Start as many items as concurrency allows
        var contextId = getOperationId();
        registerActiveThread(contextId);

        Runnable handler = () -> {
            try (var context = getContext().createChildContext(contextId, getName())) {
                while (true) {
                    if (isOperationCompleted()) {
                        return;
                    }
                    while (runningChildren.size() < maxConcurrency) {
                        if (vacancyListener != null && vacancyListener.isDone()) {
                            vacancyListener = null;
                        }
                        var next = pendingQueue.poll();
                        if (next == null) {
                            break;
                        }
                        runningChildren.put(next, true);
                        logger.debug("Executing operation {}", next.getName());
                        next.execute();
                    }
                    var child = waitForChildCompletion();
                    if (runningChildren.containsKey(child)) {
                        onItemComplete((ChildContextOperation<?>) child);
                    }
                }
            }
        };
        CompletableFuture.runAsync(handler, getContext().getDurableConfig().getExecutorService());
    }

    private BaseDurableOperation waitForChildCompletion() {
        var threadContext = getCurrentThreadContext();
        CompletableFuture<Object> future;

        synchronized (this) {
            ArrayList<CompletableFuture<BaseDurableOperation>> futures;
            futures = new ArrayList<>(runningChildren.keySet().stream()
                    .map(BaseDurableOperation::getCompletionFuture)
                    .toList());
            if (futures.size() < maxConcurrency) {
                vacancyListener = new CompletableFuture<>();
                futures.add(vacancyListener);
            }

            // future will be completed immediately if any future of the list is already completed
            future = CompletableFuture.anyOf(futures.toArray(CompletableFuture[]::new));
            // skip deregistering the current thread if there is more completed future to process
            if (!future.isDone()) {
                future.thenRun(() -> registerActiveThread(threadContext.threadId()));
                // Deregister the current thread to allow suspension
                executionManager.deregisterActiveThread(threadContext.threadId());
            }
        }
        return future.thenApply(o -> (BaseDurableOperation) o).join();
    }

    /**
     * Called by a ChildContextOperation BEFORE it closes its child context. Updates counters, checks completion
     * criteria, and either triggers the next queued item or completes the operation.
     *
     * @param child the child operation that completed
     */
    public void onItemComplete(ChildContextOperation<?> child) {
        if (!completedOperations.add(child.getOperationId())) {
            throw new IllegalStateException("Child operation " + child.getOperationId() + " completed twice");
        }

        // Evaluate child result outside the lock — child.get() may block waiting for a checkpoint response.
        logger.debug("OnItemComplete called by {}, Id: {}", child.getName(), child.getOperationId());
        boolean succeeded;
        try {
            child.get();
            logger.debug("Result succeeded - {}", child.getName());
            succeeded = true;
        } catch (Throwable e) {
            logger.debug("Child operation {} failed: {}", child.getOperationId(), e.getMessage());
            succeeded = false;
        }

        // Counter updates, completion check, and next-item dispatch must be atomic to prevent
        // the main thread's join() from seeing runningCount==0 with incomplete counters.
        synchronized (this) {
            if (succeeded) {
                succeededCount.incrementAndGet();
            } else {
                failedCount.incrementAndGet();
            }
            if (!runningChildren.containsKey(child)) {
                throw new IllegalStateException("Child operation " + child.getOperationId() + " completed twice");
            }
            runningChildren.remove(child);

            var completionStatus = canComplete();
            if (completionStatus != null) {
                handleComplete(completionStatus);
            }
        }
    }

    // ========== Completion logic ==========
    /**
     * Validates that the number of registered items is sufficient to satisfy the completion criteria. Called at join()
     * time because branches are registered incrementally and the total count is only known once the user calls join().
     *
     * @throws IllegalArgumentException if the item count cannot satisfy the criteria
     */
    protected void validateItemCount() {
        if (minSuccessful != null && minSuccessful > branches.size()) {
            throw new IllegalStateException("minSuccessful (" + minSuccessful
                    + ") exceeds the number of registered items (" + branches.size() + ")");
        }
    }

    /**
     * Checks whether the concurrency operation can be considered complete.
     *
     * @return the completion status if the operation is complete, or null if it should continue
     */
    protected ConcurrencyCompletionStatus canComplete() {
        int succeeded = succeededCount.get();
        int failed = failedCount.get();

        // If we've met the minimum successful count, we're done
        if (minSuccessful != null && succeeded >= minSuccessful) {
            return ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED;
        }

        // If we've exceeded the failure tolerance, we're done
        if (toleratedFailureCount != null && failed > toleratedFailureCount) {
            return ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED;
        }

        // All items finished — complete
        if (isAllItemsFinished()) {
            return ConcurrencyCompletionStatus.ALL_COMPLETED;
        }

        return null;
    }

    private void handleComplete(ConcurrencyCompletionStatus status) {
        synchronized (this) {
            if (isOperationCompleted()) {
                return;
            }
            handleSuccess(status);
        }
    }

    /**
     * Blocks the calling thread until the concurrency operation reaches a terminal state. Validates item count, handles
     * zero-branch case, then delegates to {@code waitForOperationCompletion()} from BaseDurableOperation.
     */
    public void join() {
        validateItemCount();
        isJoined.set(true);
        synchronized (this) {
            if (!isOperationCompleted()) {
                var completionStatus = canComplete();
                if (completionStatus != null) {
                    handleComplete(completionStatus);
                }
            }
        }

        waitForOperationCompletion();
    }

    protected List<ChildContextOperation<?>> getBranches() {
        return branches;
    }

    /** Returns true if all items have finished (no pending, no running). Used by subclasses to override canComplete. */
    protected boolean isAllItemsFinished() {
        return isJoined.get() && pendingQueue.isEmpty() && runningChildren.isEmpty();
    }
}
