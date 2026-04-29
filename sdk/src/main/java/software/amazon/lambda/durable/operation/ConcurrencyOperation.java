// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

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

    protected record ExpectedCompletionStatus(int completed, ConcurrencyCompletionStatus completionStatus) {}

    private static final Logger logger = LoggerFactory.getLogger(ConcurrencyOperation.class);

    private final int maxConcurrency;
    private final Integer minSuccessful;
    private final Integer toleratedFailureCount;
    private final OperationIdGenerator operationIdGenerator;
    private final DurableContextImpl rootContext;
    private final NestingType nestingType;

    // access by context thread only
    private final List<ChildContextOperation<?>> branches = Collections.synchronizedList(new ArrayList<>());

    // put only by context thread and consume only by consumer thread
    private final Queue<ChildContextOperation<?>> pendingQueue = new ConcurrentLinkedDeque<>();

    // set by context thread and used by consumer thread
    protected final AtomicBoolean isJoined = new AtomicBoolean(false);

    // used to wake up consumer thread for either new items or checking completion condition (isJoined changed)
    private final AtomicReference<CompletableFuture<BaseDurableOperation>> consumerThreadListener;

    protected ConcurrencyOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext,
            int maxConcurrency,
            Integer minSuccessful,
            Integer toleratedFailureCount,
            NestingType nestingType) {
        super(operationIdentifier, resultTypeToken, resultSerDes, durableContext);
        this.maxConcurrency = maxConcurrency;
        this.minSuccessful = minSuccessful;
        this.toleratedFailureCount = toleratedFailureCount;
        this.operationIdGenerator = new OperationIdGenerator(getOperationId());
        // root context of the concurrency operation is always non-virtual
        this.rootContext = durableContext.createChildContext(getOperationId(), getName(), false);
        this.consumerThreadListener = new AtomicReference<>(new CompletableFuture<>());
        this.nestingType = nestingType;
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
     * @param <R> the result type of the child operation
     * @return a new ChildContextOperation
     */
    protected <R> ChildContextOperation<R> createItem(
            String operationId,
            String name,
            Function<DurableContext, R> function,
            TypeToken<R> resultType,
            SerDes serDes,
            OperationSubType branchSubType) {
        return new ChildContextOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, branchSubType),
                function,
                resultType,
                RunInChildContextConfig.builder().serDes(serDes).build(),
                rootContext,
                nestingType == NestingType.FLAT,
                this);
    }

    /** Called when the concurrency operation completes. Subclasses define checkpointing behavior. */
    protected abstract void handleCompletion(ConcurrencyCompletionStatus concurrencyCompletionStatus);

    // ========== Concurrency control ==========

    /**
     * Creates and enqueues an item without starting execution. Use {@link #executeItems(ExpectedCompletionStatus)} to
     * begin execution after all items have been enqueued. This prevents early termination from blocking item creation
     * when all items are known upfront (e.g., map operations).
     */
    protected <R> ChildContextOperation<R> enqueueItem(
            String name,
            Function<DurableContext, R> function,
            TypeToken<R> resultType,
            SerDes serDes,
            OperationSubType branchSubType,
            boolean skipped) {
        var operationId = this.operationIdGenerator.nextOperationId();
        var childOp = createItem(operationId, name, function, resultType, serDes, branchSubType);
        branches.add(childOp);
        if (!skipped) {
            logger.debug("Item enqueued {}", name);
            pendingQueue.add(childOp);
        }
        // notify the consumer thread a new item is available
        notifyConsumerThread();
        return childOp;
    }

    private void notifyConsumerThread() {
        synchronized (completionFuture) {
            consumerThreadListener.get().complete(null);
        }
    }

    /** Starts execution of all enqueued items. */
    protected void executeItems() {
        executeItems(null);
    }

    /** Starts execution of all enqueued items until the expectedCompletionStatus is met. */
    protected void executeItems(ExpectedCompletionStatus expectedCompletionStatus) {
        // variables accessed only by the consumer thread. Put them here to avoid accidentally used by other threads
        Set<BaseDurableOperation> runningChildren = new HashSet<>();
        AtomicInteger succeededCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        Runnable consumer = () -> {
            try {
                while (true) {
                    // Set a new future if it's completed so that it will be able to receive a notification of
                    // new items when the thread is checking completion condition and processing
                    // the queued items below.
                    synchronized (completionFuture) {
                        if (consumerThreadListener.get() != null
                                && consumerThreadListener.get().isDone()) {
                            consumerThreadListener.set(new CompletableFuture<>());
                        }
                    }

                    // Process completion condition. Quit the loop if the condition is met.
                    if (isOperationCompleted()) {
                        return;
                    }
                    var completionStatus =
                            canComplete(succeededCount, failedCount, runningChildren, expectedCompletionStatus);
                    if (completionStatus != null) {
                        handleCompletion(completionStatus);
                        return;
                    }

                    // process new items in the queue
                    while (runningChildren.size() < maxConcurrency && !pendingQueue.isEmpty()) {
                        var next = pendingQueue.poll();
                        runningChildren.add(next);
                        logger.debug("Executing operation {}", next.getName());
                        next.execute();
                    }

                    // If consumerThreadListener has been completed when processing above, waitForChildCompletion will
                    // immediately return null and repeat the above again
                    var child = waitForChildCompletion(
                            succeededCount, failedCount, runningChildren, expectedCompletionStatus);

                    // child may be null if the consumer thread is woken up due to new items added or completion
                    // condition
                    // changed
                    if (child != null) {
                        if (runningChildren.contains(child)) {
                            runningChildren.remove(child);
                            onItemComplete(succeededCount, failedCount, (ChildContextOperation<?>) child);
                        } else {
                            throw new IllegalStateException("Unexpected completion: " + child);
                        }
                    }
                }
            } catch (Throwable ex) {
                handleException(ex);
            }
        };
        // run consumer in the user thread pool, although it's not a real user thread
        runUserHandler(consumer, ThreadType.CONTEXT);
    }

    private void handleException(Throwable ex) {
        Throwable throwable = ExceptionHelper.unwrapCompletableFuture(ex);
        if (throwable instanceof SuspendExecutionException suspendExecutionException) {
            // Rethrow Error immediately — do not checkpoint
            throw suspendExecutionException;
        }
        if (throwable instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
            throw terminateExecution(unrecoverableDurableExecutionException);
        }

        throw terminateExecutionWithIllegalDurableOperationException(
                String.format("Unexpected exception in concurrency operation: %s", throwable));
    }

    private BaseDurableOperation waitForChildCompletion(
            AtomicInteger succeededCount,
            AtomicInteger failedCount,
            Set<BaseDurableOperation> runningChildren,
            ExpectedCompletionStatus expectedCompletionStatus) {
        var threadContext = getCurrentThreadContext();
        CompletableFuture<Object> future;

        synchronized (completionFuture) {
            // check again in synchronized block to prevent race conditions
            if (isOperationCompleted()) {
                return null;
            }
            var completionStatus = canComplete(succeededCount, failedCount, runningChildren, expectedCompletionStatus);
            if (completionStatus != null) {
                return null;
            }
            ArrayList<CompletableFuture<BaseDurableOperation>> futures;
            futures = new ArrayList<>(runningChildren.stream()
                    .map(BaseDurableOperation::getCompletionFuture)
                    .toList());
            if (futures.size() < maxConcurrency) {
                // add a future to listen to the new items if there is a vacancy
                consumerThreadListener.compareAndSet(null, new CompletableFuture<>());
                futures.add(consumerThreadListener.get());
            }

            // future will be completed immediately if any future of the list is already completed
            future = CompletableFuture.anyOf(futures.toArray(CompletableFuture[]::new));
            // skip deregistering the current thread if there is more completed future to process
            if (!future.isDone()) {
                future = future.thenApply(o -> {
                    registerActiveThread(threadContext.threadId());
                    return o;
                });
                // Deregister the current thread to allow suspension
                deregisterActiveThread(threadContext.threadId());
            }
        }
        try {
            return future.thenApply(o -> (BaseDurableOperation) o).join();
        } catch (Throwable throwable) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(throwable));
            throw throwable;
        }
    }

    /**
     * Called by a ChildContextOperation BEFORE it closes its child context. Updates counters, checks completion
     * criteria, and either triggers the next queued item or completes the operation.
     *
     * @param child the child operation that completed
     */
    private void onItemComplete(
            AtomicInteger succeededCount, AtomicInteger failedCount, ChildContextOperation<?> child) {
        // Evaluate child result outside the lock — child.get() may block waiting for a checkpoint response.
        logger.debug("OnItemComplete called by {}, Id: {}", child.getName(), child.getOperationId());
        try {
            child.get();
            logger.debug("Result succeeded - {}", child.getName());
            succeededCount.incrementAndGet();
        } catch (Throwable e) {
            logger.debug("Child operation {} failed: {}", child.getOperationId(), e.getMessage());
            failedCount.incrementAndGet();
        }
    }

    // ========== Completion logic ==========
    /**
     * Checks whether the concurrency operation can be considered complete.
     *
     * @return the completion status if the operation is complete, or null if it should continue
     */
    private ConcurrencyCompletionStatus canComplete(
            AtomicInteger succeededCount,
            AtomicInteger failedCount,
            Set<BaseDurableOperation> runningChildren,
            ExpectedCompletionStatus expectedCompletionStatus) {
        int succeeded = succeededCount.get();
        int failed = failedCount.get();

        if (expectedCompletionStatus != null) {
            if (succeeded + failed >= expectedCompletionStatus.completed) {
                return expectedCompletionStatus.completionStatus;
            }

            // if expected completion status is not null, we always complete all the children previously completed
            return null;
        }

        // If we've met the minimum successful count, we're done
        if (minSuccessful != null && succeeded >= minSuccessful) {
            return ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED;
        }

        // If we've exceeded the failure tolerance, we're done
        if (toleratedFailureCount != null && failed > toleratedFailureCount) {
            return ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED;
        }

        // All items finished — complete
        // This condition relies on isJoined, so the consumer will wake up and check this again when
        // isJoined is set to true.
        if (isJoined.get() && pendingQueue.isEmpty() && runningChildren.isEmpty()) {
            return ConcurrencyCompletionStatus.ALL_COMPLETED;
        }

        return null;
    }

    /**
     * Blocks the calling thread until the concurrency operation reaches a terminal state. Validates item count, handles
     * zero-branch case, then delegates to {@code waitForOperationCompletion()} from BaseDurableOperation.
     */
    protected void join() {
        if (minSuccessful != null && minSuccessful > branches.size()) {
            throw new IllegalStateException("minSuccessful (" + minSuccessful
                    + ") exceeds the number of registered items (" + branches.size() + ")");
        }
        isJoined.set(true);

        // Notify the consumer thread this concurrency operation is joined. Consumer thread need to check the
        // completion condition again.
        notifyConsumerThread();
        waitForOperationCompletion();
    }

    protected List<ChildContextOperation<?>> getBranches() {
        return branches;
    }
}
