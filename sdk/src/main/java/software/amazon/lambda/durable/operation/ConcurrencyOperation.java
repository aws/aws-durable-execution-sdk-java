// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
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
public abstract class ConcurrencyOperation<T> extends BaseDurableOperation<T> {

    private static final Logger logger = LoggerFactory.getLogger(ConcurrencyOperation.class);

    private final int maxConcurrency;
    private final Integer minSuccessful;
    private final Integer toleratedFailureCount;
    private final AtomicInteger succeededCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger runningCount = new AtomicInteger(0);
    private final AtomicBoolean isJoined = new AtomicBoolean(false);
    private final Queue<ChildContextOperation<?>> pendingQueue = new ConcurrentLinkedDeque<>();
    private final List<ChildContextOperation<?>> childOperations = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> completedOperations = Collections.synchronizedSet(new HashSet<String>());
    private final OperationIdGenerator operationIdGenerator;
    private final DurableContextImpl rootContext;
    private ConcurrencyCompletionStatus completionStatus;

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
     * @param serDes the serializer/deserializer
     * @param parentContext the parent durable context
     * @param <R> the result type of the child operation
     * @return a new ChildContextOperation
     */
    protected abstract <R> ChildContextOperation<R> createItem(
            String operationId,
            String name,
            Function<DurableContext, R> function,
            TypeToken<R> resultType,
            SerDes serDes,
            DurableContextImpl parentContext);

    /** Called when the concurrency operation succeeds. Subclasses define checkpointing behavior. */
    protected abstract void handleSuccess(ConcurrencyCompletionStatus concurrencyCompletionStatus);

    // ========== Concurrency control ==========

    /**
     * Adds a new item to this concurrency operation. Creates the child operation and either starts it immediately or
     * enqueues it if maxConcurrency is reached.
     *
     * @param name the name of the item
     * @param function the user function to execute
     * @param resultType the result type token
     * @param serDes the serializer/deserializer
     * @param <R> the result type of the child operation
     * @return the created ChildContextOperation
     */
    public <R> ChildContextOperation<R> addItem(
            String name, Function<DurableContext, R> function, TypeToken<R> resultType, SerDes serDes) {
        if (isOperationCompleted()) throw new IllegalStateException("Cannot add items to a completed operation");
        var operationId = this.operationIdGenerator.nextOperationId();
        var childOp = createItem(operationId, name, function, resultType, serDes, this.rootContext);
        childOperations.add(childOp);
        pendingQueue.add(childOp);
        logger.debug("Item added {}", name);
        executeNextItemIfAllowed();
        return childOp;
    }

    /**
     * Creates and enqueues an item without starting execution. Use {@link #startPendingItems()} to begin execution
     * after all items have been enqueued. This prevents early termination from blocking item creation when all items
     * are known upfront (e.g., map operations).
     */
    protected <R> ChildContextOperation<R> enqueueItem(
            String name, Function<DurableContext, R> function, TypeToken<R> resultType, SerDes serDes) {
        var operationId = this.operationIdGenerator.nextOperationId();
        var childOp = createItem(operationId, name, function, resultType, serDes, this.rootContext);
        childOperations.add(childOp);
        pendingQueue.add(childOp);
        logger.debug("Item enqueued {}", name);
        return childOp;
    }

    /**
     * Starts executing enqueued items up to maxConcurrency. Called after all items have been enqueued via
     * {@link #enqueueItem}.
     */
    protected void startPendingItems() {
        // Start as many items as concurrency allows
        while (true) {
            synchronized (this) {
                if (isOperationCompleted()) return;
                if (runningCount.get() >= maxConcurrency) return;
                var next = pendingQueue.poll();
                if (next == null) return;
                runningCount.incrementAndGet();
                logger.debug("Executing operation {}", next.getName());
                next.execute();
            }
        }
    }

    /**
     * Starts the next queued item if the running count is below maxConcurrency and the operation hasn't completed yet.
     * Must be called within {@code synchronized (pendingQueue)}.
     */
    private void executeNextItemIfAllowed() {
        synchronized (this) {
            if (isOperationCompleted()) return;
            if (runningCount.get() >= maxConcurrency) return;
            var next = pendingQueue.poll();
            if (next == null) return;
            runningCount.incrementAndGet();
            logger.debug("Executing operation {}", next.getName());
            next.execute();
        }
    }

    /**
     * Called by a ChildContextOperation BEFORE it closes its child context. Updates counters, checks completion
     * criteria, and either triggers the next queued item or completes the operation.
     *
     * @param child the child operation that completed
     */
    public void onItemComplete(ChildContextOperation<?> child) {
        if (!completedOperations.add(child.getOperationId())) {
            return;
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
            runningCount.decrementAndGet();

            this.completionStatus = canComplete();
            if (this.completionStatus != null) {
                handleComplete(this.completionStatus);
            } else {
                executeNextItemIfAllowed();
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
        if (minSuccessful != null && minSuccessful > getTotalItems()) {
            throw new IllegalArgumentException("minSuccessful (" + minSuccessful
                    + ") exceeds the number of registered items (" + getTotalItems() + ")");
        }
    }

    /**
     * Checks whether the concurrency operation can be considered complete.
     *
     * @return the completion status if the operation is complete, or null if it should continue
     */
    protected ConcurrencyCompletionStatus canComplete() {
        int succeeded = getSucceededCount();
        int failed = getFailedCount();

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
            this.completionStatus = canComplete();
            if (this.completionStatus != null) {
                handleComplete(this.completionStatus);
            }
        }

        waitForOperationCompletion();
    }

    protected int getSucceededCount() {
        return succeededCount.get();
    }

    protected int getFailedCount() {
        return failedCount.get();
    }

    protected int getTotalItems() {
        return childOperations.size();
    }

    protected ConcurrencyCompletionStatus getCompletionStatus() {
        return completionStatus;
    }

    protected List<ChildContextOperation<?>> getChildOperations() {
        return Collections.unmodifiableList(childOperations);
    }

    /** Returns true if all items have finished (no pending, no running). Used by subclasses to override canComplete. */
    protected boolean isAllItemsFinished() {
        return isJoined.get() && pendingQueue.isEmpty() && runningCount.get() == 0;
    }
}
