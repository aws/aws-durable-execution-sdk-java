// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Abstract base class for concurrent execution of multiple child context operations.
 *
 * <p>Encapsulates shared concurrency logic: queue-based concurrency control, success/failure counting, and completion
 * checking. Both {@code ParallelOperation} and a future {@code MapOperation} extend this base.
 *
 * <p>Key design points:
 *
 * <ul>
 *   <li>Does NOT register its own thread — child context threads handle all suspension
 *   <li>Uses a pending queue + running counter for concurrency control
 *   <li>Completion is determined by {@code minSuccessful}, {@code failureRateThreshold} and
 *       {@code toleratedFailureCount}
 *   <li>When a child suspends, the running count is NOT decremented
 * </ul>
 *
 * @param <T> the result type of this operation
 */
public abstract class ConcurrencyOperation<T> extends BaseDurableOperation<T> {

    private static final Logger logger = LoggerFactory.getLogger(ConcurrencyOperation.class);

    private final int maxConcurrency;
    private final int minSuccessful;
    private final int toleratedFailureCount;
    private final double failureRateThreshold;
    private final AtomicInteger succeededCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger runningCount = new AtomicInteger(0);
    private final AtomicBoolean isJoined = new AtomicBoolean(false);
    private final Queue<ChildContextOperation<?>> pendingQueue = new ConcurrentLinkedDeque<>();
    private final List<ChildContextOperation<?>> childOperations = Collections.synchronizedList(new ArrayList<>());
    private ConcurrencyCompletionStatus completionStatus;

    protected ConcurrencyOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContext durableContext,
            int maxConcurrency,
            int minSuccessful,
            int toleratedFailureCount,
            double failureRateThreshold) {
        super(operationIdentifier, resultTypeToken, resultSerDes, durableContext);
        this.maxConcurrency = maxConcurrency;
        this.minSuccessful = minSuccessful;
        this.toleratedFailureCount = toleratedFailureCount;
        this.failureRateThreshold = failureRateThreshold;
    }

    protected ConcurrencyOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContext durableContext,
            int maxConcurrency,
            int minSuccessful,
            int toleratedFailureCount) {
        this(
                operationIdentifier,
                resultTypeToken,
                resultSerDes,
                durableContext,
                maxConcurrency,
                minSuccessful,
                toleratedFailureCount,
                100);
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
            DurableContext parentContext);

    /**
     * Called when the concurrency operation succeeds (minSuccessful threshold met). Subclasses define checkpointing
     * behavior.
     */
    protected abstract void handleSuccess();

    /** Called when the concurrency operation fails. Subclasses define checkpointing and exception behavior. */
    protected abstract void handleFailure();

    // ========== Concurrency control ==========

    /**
     * Adds a new item to this concurrency operation. Creates the child operation and either starts it immediately or
     * enqueues it if maxConcurrency is reached.
     *
     * @param name the name of the item
     * @param function the user function to execute
     * @param resultType the result type token
     * @param serDes the serializer/deserializer
     * @param parentContext the parent durable context for creating child operations
     * @param <R> the result type of the child operation
     * @return the created ChildContextOperation
     */
    public <R> ChildContextOperation<R> addItem(
            String name, Function<DurableContext, R> function, TypeToken<R> resultType, SerDes serDes) {
        var operationId = getContext().nextOperationId();
        var childOp = createItem(operationId, name, function, resultType, serDes, getContext());
        childOperations.add(childOp);
        pendingQueue.add(childOp);
        executeNextItemIfAllowed();
        return childOp;
    }

    /**
     * Starts the next queued item if the running count is below maxConcurrency and the operation hasn't completed yet.
     * Must be called within {@code synchronized (pendingQueue)}.
     */
    private void executeNextItemIfAllowed() {
        synchronized (this) {
            if (isOperationCompleted()) return;
            if (maxConcurrency != -1 && runningCount.get() >= maxConcurrency) return;
            var next = pendingQueue.poll();
            if (next == null) return;
            runningCount.incrementAndGet();
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
        runningCount.decrementAndGet();

        try {
            child.get();
            succeededCount.incrementAndGet();
        } catch (Exception e) {
            failedCount.incrementAndGet();
            logger.trace("Child operation {} failed: {}", child.getOperationId(), e.getMessage());
        }

        if (canComplete()) {
            handleComplete();
        } else {
            executeNextItemIfAllowed();
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
        int totalItems = childOperations.size();

        if (minSuccessful > totalItems) {
            throw new IllegalArgumentException("minSuccessful (" + minSuccessful
                    + ") exceeds the number of registered items (" + totalItems + ")");
        }
    }

    /**
     * Checks whether the concurrency operation can be considered complete.
     *
     * @return true if enough items succeeded, too many failed, or not enough remaining items to reach minSuccessful
     */
    protected boolean canComplete() {
        int totalItems = childOperations.size();
        int succeeded = succeededCount.get();
        int failed = failedCount.get();

        // If we've met the minimum successful count, we're done
        if (minSuccessful != -1 && succeeded >= minSuccessful) {
            completionStatus = ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED;
            return true;
        }

        // If we've exceeded the failure tolerance, we're done
        if ((minSuccessful == -1 && failed > 0)
                || failed > toleratedFailureCount
                || (double) failed / totalItems > failureRateThreshold) {
            completionStatus = ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED;
            return true;
        }

        // This will only happens when minSuccessful == -1 and user calls join()
        if (isJoined.get() && minSuccessful == -1 && pendingQueue.isEmpty() && runningCount.get() == 0) {
            completionStatus = ConcurrencyCompletionStatus.ALL_COMPLETED;
            return true;
        }

        return false;
    }

    private void handleComplete() {
        if (isOperationCompleted()) {
            return;
        }
        if (completionStatus.isSucceeded()) {
            handleSuccess();
        } else {
            handleFailure();
        }
        synchronized (completionFuture) {
            completionFuture.complete(null);
        }
    }

    /**
     * Blocks the calling thread until the concurrency operation reaches a terminal state. Validates item count, handles
     * zero-branch case, then delegates to {@code waitForOperationCompletion()} from BaseDurableOperation.
     */
    protected void join() {
        validateItemCount();
        isJoined.set(true);
        if (childOperations.isEmpty()) {
            return;
        }

        if (canComplete()) {
            handleComplete();
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

    protected List<ChildContextOperation<?>> getChildOperations() {
        return Collections.unmodifiableList(childOperations);
    }
}
