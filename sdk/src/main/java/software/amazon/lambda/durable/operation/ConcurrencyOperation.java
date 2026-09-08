// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
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
 *   <li>A single coordinator owns scheduling state and completion counters
 *   <li>Child threads publish completion events through a blocking queue
 *   <li>Uses a FIFO pending queue for deterministic branch admission
 *   <li>Completion is determined by {@link CompletionConfig#completionDecisionFunction()}
 *   <li>When a child suspends, its concurrency slot is retained
 * </ul>
 *
 * @param <T> the result type of this operation
 */
public abstract class ConcurrencyOperation<T> extends SerializableDurableOperation<T> {

    protected record ExpectedCompletionStatus(int completed, CompletionConfig.CompletionDecision completionDecision) {}

    private record CoordinatorEvent(ChildContextOperation<?> completedChild, Throwable failure) {
        private static CoordinatorEvent stateChanged() {
            return new CoordinatorEvent(null, null);
        }

        private static CoordinatorEvent childCompleted(ChildContextOperation<?> child, Throwable failure) {
            return new CoordinatorEvent(child, failure);
        }

        private static CoordinatorEvent failed(Throwable failure) {
            return new CoordinatorEvent(null, failure);
        }
    }

    private static final class CoordinatorState {
        private final Set<ChildContextOperation<?>> runningChildren = new HashSet<>();
        private int succeededCount;
        private int failedCount;
    }

    private static final Logger logger = LoggerFactory.getLogger(ConcurrencyOperation.class);

    private final int maxConcurrency;
    private final Function<CompletionConfig.CompletionStatus, CompletionConfig.CompletionDecision> shouldComplete;
    private final OperationIdGenerator operationIdGenerator;
    private final DurableContextImpl rootContext;
    private final NestingType nestingType;

    // added by the context thread and read by the coordinator and result aggregation
    private final List<ChildContextOperation<?>> branches = Collections.synchronizedList(new ArrayList<>());

    // produced by the context thread and consumed by the coordinator
    private final Queue<ChildContextOperation<?>> pendingQueue = new ConcurrentLinkedQueue<>();

    // workers publish events; only the coordinator consumes them and mutates scheduling state
    private final BlockingQueue<CoordinatorEvent> coordinatorEvents = new LinkedBlockingQueue<>();

    // guarded by completionFuture
    private boolean stateChangedQueued;
    private boolean coordinatorWaiting;

    // set by context thread and used by consumer thread
    protected final AtomicBoolean isJoined = new AtomicBoolean(false);

    protected ConcurrencyOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext,
            int maxConcurrency,
            Function<CompletionConfig.CompletionStatus, CompletionConfig.CompletionDecision> shouldComplete,
            NestingType nestingType) {
        super(operationIdentifier, resultTypeToken, resultSerDes, durableContext);
        this.maxConcurrency = maxConcurrency;
        this.shouldComplete = Objects.requireNonNull(shouldComplete, "shouldComplete cannot be null");
        this.operationIdGenerator = new OperationIdGenerator(getOperationId());
        // root context of the concurrency operation is always non-virtual
        this.rootContext = durableContext.createChildContext(getOperationId(), getName(), false);
        this.nestingType = nestingType;
        completionFuture.whenComplete((ignored, failure) -> {
            if (failure != null) {
                publishCoordinatorEvent(CoordinatorEvent.failed(failure));
            }
        });
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
                OperationIdentifier.of(operationId, name, branchSubType),
                function,
                resultType,
                RunInChildContextConfig.builder()
                        .serDes(serDes)
                        .isVirtual(nestingType == NestingType.FLAT)
                        .build(),
                rootContext,
                this);
    }

    /** Called when the concurrency operation completes. Subclasses define checkpointing behavior. */
    protected abstract void handleCompletion(CompletionConfig.CompletionDecision completionDecision);

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
        notifyCoordinatorStateChanged();
        return childOp;
    }

    private void notifyCoordinatorStateChanged() {
        synchronized (completionFuture) {
            if (!stateChangedQueued) {
                stateChangedQueued = true;
                publishCoordinatorEventLocked(CoordinatorEvent.stateChanged());
            }
        }
    }

    private void publishCoordinatorEvent(CoordinatorEvent event) {
        synchronized (completionFuture) {
            publishCoordinatorEventLocked(event);
        }
    }

    private void publishCoordinatorEventLocked(CoordinatorEvent event) {
        coordinatorEvents.add(event);
        if (coordinatorWaiting) {
            coordinatorWaiting = false;
            if (event.failure() == null) {
                registerActiveThread(getOperationId());
            }
        }
    }

    /** Starts execution of all enqueued items. */
    protected void executeItems() {
        executeItems(null);
    }

    /** Starts execution of all enqueued items until the expectedCompletionStatus is met. */
    protected void executeItems(ExpectedCompletionStatus expectedCompletionStatus) {
        // run consumer in the user thread pool, although it's not a real user thread
        runUserHandler(() -> runCoordinator(expectedCompletionStatus), ThreadType.CONTEXT);
    }

    private void runCoordinator(ExpectedCompletionStatus expectedCompletionStatus) {
        var state = new CoordinatorState();

        try {
            while (!isOperationCompleted()) {
                var completionDecision = canComplete(state.succeededCount, state.failedCount, expectedCompletionStatus);
                if (completionDecision != null) {
                    handleCompletion(completionDecision);
                    return;
                }
                startPendingItems(state);
                processCoordinatorEvent(waitForCoordinatorEvent(), state);
            }
        } catch (Throwable ex) {
            handleException(ex);
        }
    }

    private void startPendingItems(CoordinatorState state) {
        ChildContextOperation<?> next;
        while (state.runningChildren.size() < maxConcurrency && (next = pendingQueue.poll()) != null) {
            var child = next;
            state.runningChildren.add(child);
            child.getCompletionFuture()
                    .whenComplete((ignored, failure) ->
                            publishCoordinatorEvent(CoordinatorEvent.childCompleted(child, failure)));
            logger.debug("Executing operation {}", child.getName());
            child.execute();
        }
    }

    private void processCoordinatorEvent(CoordinatorEvent event, CoordinatorState state) {
        if (event.failure() != null) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(event.failure()));
        }
        var child = event.completedChild();
        if (child == null) {
            synchronized (completionFuture) {
                stateChangedQueued = false;
            }
            return;
        }
        if (!state.runningChildren.remove(child)) {
            throw new IllegalStateException("Unexpected completion: " + child);
        }
        onItemComplete(state, child);
    }

    private CoordinatorEvent waitForCoordinatorEvent() {
        var threadContext = getCurrentThreadContext();
        synchronized (completionFuture) {
            var event = coordinatorEvents.poll();
            if (event != null || isOperationCompleted()) {
                return event != null ? event : CoordinatorEvent.stateChanged();
            }
            coordinatorWaiting = true;
            deregisterActiveThread(threadContext.threadId());
        }

        try {
            return coordinatorEvents.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency coordinator interrupted", e);
        }
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

    /**
     * Called by a ChildContextOperation BEFORE it closes its child context. Updates counters, checks completion
     * criteria, and either triggers the next queued item or completes the operation.
     *
     * @param child the child operation that completed
     */
    private void onItemComplete(CoordinatorState state, ChildContextOperation<?> child) {
        // Evaluate child result outside the lock — child.get() may block waiting for a checkpoint response.
        logger.debug("OnItemComplete called by {}, Id: {}", child.getName(), child.getOperationId());
        try {
            child.get();
            logger.debug("Result succeeded - {}", child.getName());
            state.succeededCount++;
        } catch (Throwable e) {
            logger.debug("Child operation {} failed: {}", child.getOperationId(), e.getMessage());
            state.failedCount++;
        }
    }

    // ========== Completion logic ==========
    /**
     * Checks whether the concurrency operation can be considered complete.
     *
     * @return the completion status if the operation is complete, or null if it should continue
     */
    private CompletionConfig.CompletionDecision canComplete(
            int succeeded, int failed, ExpectedCompletionStatus expectedCompletionStatus) {
        if (expectedCompletionStatus != null) {
            if (succeeded + failed >= expectedCompletionStatus.completed) {
                return expectedCompletionStatus.completionDecision;
            }

            // if expected completion status is not null, we always complete all the children previously completed
            return null;
        }

        var decision = Objects.requireNonNull(
                shouldComplete.apply(completionStatus(succeeded, failed)),
                "shouldComplete must return a completion decision");
        return decision.shouldComplete() ? decision : null;
    }

    private CompletionConfig.CompletionStatus completionStatus(int succeeded, int failed) {
        return new CompletionConfig.CompletionStatus(
                succeeded, failed, succeeded + failed, branches.size(), allItemsRegistered());
    }

    private boolean allItemsRegistered() {
        return isJoined.get();
    }

    /**
     * Blocks the calling thread until the concurrency operation reaches a terminal state. Validates item count, handles
     * zero-branch case, then delegates to {@code waitForOperationCompletion()} from BaseDurableOperation.
     */
    protected void join() {
        isJoined.set(true);

        notifyCoordinatorStateChanged();
        waitForOperationCompletion();
    }

    protected List<ChildContextOperation<?>> getBranches() {
        return branches;
    }
}
