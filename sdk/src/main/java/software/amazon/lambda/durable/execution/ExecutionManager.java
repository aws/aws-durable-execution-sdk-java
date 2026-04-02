// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

/**
 * Central manager for durable execution coordination.
 *
 * <p>Consolidates:
 *
 * <ul>
 *   <li>Execution state (operations, checkpoint token)
 *   <li>Thread lifecycle (registration/deregistration)
 *   <li>Checkpoint batching (via CheckpointManager)
 *   <li>Checkpoint result handling (CheckpointManager callback)
 *   <li>Polling (for waits and retries)
 * </ul>
 *
 * <p>This is the single entry point for all execution coordination. Internal coordination (polling, checkpointing) uses
 * a dedicated SDK thread pool, while user-defined operations run on a customer-configured executor.
 *
 * <p>Operations are keyed by their globally unique operation ID. Child context operations use prefixed IDs (e.g.,
 * "1-1", "1-2") to avoid collisions with root-level operations.
 *
 * @see InternalExecutor
 */
public class ExecutionManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionManager.class);

    // ===== Execution State =====
    private final Map<String, Operation> operationStorage;
    private final Operation executionOp;
    private final String durableExecutionArn;
    private final AtomicReference<ExecutionMode> executionMode;
    private final DurableConfig durableConfig;

    // ===== Thread Coordination =====
    private final Map<String, BaseDurableOperation> registeredOperations = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> activeThreads = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<ThreadContext> currentThreadContext = new ThreadLocal<>();
    private final CompletableFuture<Void> executionExceptionFuture = new CompletableFuture<>();

    // ===== Checkpoint Batching =====
    private final CheckpointManager checkpointManager;

    public ExecutionManager(DurableExecutionInput input, DurableConfig config) {
        durableConfig = config;
        this.durableExecutionArn = input.durableExecutionArn();

        // Create checkpoint batcher for internal coordination
        this.checkpointManager =
                new CheckpointManager(config, durableExecutionArn, input.checkpointToken(), this::onCheckpointComplete);

        this.operationStorage = checkpointManager.fetchAllPages(input.initialExecutionState()).stream()
                .collect(Collectors.toConcurrentMap(Operation::id, op -> op));

        // Start in REPLAY mode if we have more than just the initial EXECUTION operation
        this.executionMode =
                new AtomicReference<>(operationStorage.size() > 1 ? ExecutionMode.REPLAY : ExecutionMode.EXECUTION);

        // parse durableExecutionArn and get the last part after / which is the invocation id
        var durableExecutionArnParts = durableExecutionArn.split("/", -1);
        var invocationId = durableExecutionArnParts[durableExecutionArnParts.length - 1];
        executionOp = operationStorage.get(invocationId);

        // Validate initial operation is an EXECUTION operation
        if (executionOp == null) {
            throw new IllegalStateException("EXECUTION operation not found");
        }
        logger.debug("DurableExecution.execute() called");
        logger.debug("DurableExecutionArn: {}", durableExecutionArn);
        logger.debug("Initial operations count: {}", operationStorage.size());
        logger.debug("EXECUTION operation found: {}", executionOp.id());
    }

    // ===== State Management =====

    /** Returns the ARN of the durable execution being managed. */
    public String getDurableExecutionArn() {
        return durableExecutionArn;
    }

    /** Returns {@code true} if the execution is currently replaying completed operations. */
    public boolean isReplaying() {
        return executionMode.get() == ExecutionMode.REPLAY;
    }

    /** Registers an operation so it can receive checkpoint completion notifications. */
    public void registerOperation(BaseDurableOperation operation) {
        registeredOperations.put(operation.getOperationId(), operation);
    }

    // ===== Checkpoint Completion Handler =====
    /** Called by CheckpointManager when a checkpoint completes. Updates operationStorage and notify operations . */
    private void onCheckpointComplete(List<Operation> newOperations) {
        newOperations.forEach(op -> {
            // Update operation storage
            operationStorage.put(op.id(), op);
            // call registered operation's onCheckpointComplete method for completed operations
            registeredOperations.computeIfPresent(op.id(), (id, operation) -> {
                operation.onCheckpointComplete(op);
                return operation;
            });
        });
    }

    /**
     * Gets all child operations for a given operationId.
     *
     * @param operationId the operationId to get children for
     * @return List of child operations for the given operationId
     */
    public List<Operation> getChildOperations(String operationId) {
        // todo: this is O(n) - consider an improvement if performance becomes an issue
        var children = new ArrayList<Operation>();
        for (Operation op : operationStorage.values()) {
            if (Objects.equals(op.parentId(), operationId)) {
                children.add(op);
            }
        }
        return children;
    }

    /**
     * Gets an operation by its globally unique operationId, and updates replay state. Transitions from REPLAY to
     * EXECUTION mode if the operation is not found or is not in a terminal state (still in progress).
     *
     * @param operationId the globally unique operation ID (e.g., "1" for root, "1-1" for child context)
     * @return the existing operation, or null if not found (first execution)
     */
    public Operation getOperationAndUpdateReplayState(String operationId) {
        var existing = operationStorage.get(operationId);
        if (executionMode.get() == ExecutionMode.REPLAY && (existing == null || !isTerminalStatus(existing.status()))) {
            if (executionMode.compareAndSet(ExecutionMode.REPLAY, ExecutionMode.EXECUTION)) {
                logger.debug("Transitioned to EXECUTION mode at operation '{}'", operationId);
            }
        }
        return existing;
    }

    /** Returns the initial EXECUTION operation from the checkpoint state. */
    public Operation getExecutionOperation() {
        return executionOp;
    }

    /**
     * Checks whether there are any cached operations for the given parent context ID. Used to initialize per-context
     * replay state — a context starts in replay mode if the ExecutionManager has cached operations belonging to it.
     *
     * @param parentId the context ID to check (null for root context)
     * @return true if at least one operation exists with the given parentId
     */
    public boolean hasOperationsForContext(String parentId) {
        return operationStorage.values().stream().anyMatch(op -> Objects.equals(op.parentId(), parentId));
    }

    // ===== Thread Coordination =====
    /** Sets the current thread's ThreadContext (threadId and threadType). Called when a user thread is started. */
    public void setCurrentThreadContext(ThreadContext threadContext) {
        currentThreadContext.set(threadContext);
    }

    /** Returns the current thread's ThreadContext (threadId and threadType), or null if not set. */
    public ThreadContext getCurrentThreadContext() {
        return currentThreadContext.get();
    }

    /**
     * Registers a thread as active.
     *
     * @see ThreadContext
     */
    public void registerActiveThread(String threadId) {
        synchronized (activeThreads) {
            if (activeThreads.add(threadId)) {
                logger.trace("Registered thread '{}' as active. Active threads: {}", threadId, activeThreads.size());
            } else {
                logger.warn("Thread '{}' already registered as active", threadId);
            }
        }
    }

    /**
     * Mark a thread as inactive. If no threads remain, suspends the execution.
     *
     * @param threadId the thread ID to deregister
     */
    public void deregisterActiveThread(String threadId) {
        // Skip if already suspended
        if (executionExceptionFuture.isDone()) {
            return;
        }

        // Add synchronized block to avoid remove then check race condition and make sure that
        // the suspendExecution is called only once
        synchronized (activeThreads) {
            boolean removed = activeThreads.remove(threadId);
            if (removed) {
                logger.trace("Deregistered thread '{}' Active threads: {}", threadId, activeThreads.size());
            } else {
                logger.warn("Thread '{}' not active, cannot deregister", threadId);
            }

            if (activeThreads.isEmpty()) {
                logger.info("No active threads remaining - suspending execution");
                preSuspendCheck();
                suspendExecution();
            }
        }
    }

    private void preSuspendCheck() {
        var hasAnyPendingOperation = operationStorage.values().stream().anyMatch(o -> switch (o.type()) {
            case STEP -> o.status() == OperationStatus.PENDING;
            case WAIT, CALLBACK -> o.status() == OperationStatus.STARTED;
            case CHAINED_INVOKE -> o.status() == OperationStatus.PENDING || o.status() == OperationStatus.STARTED;
            default -> false;
        });

        if (!hasAnyPendingOperation) {
            logger.warn("Invalid suspension. No operation is pending");
        }
    }

    // ===== Checkpointing =====

    // This method will checkpoint the operation updates to the durable backend and return a future which completes
    // when the checkpoint completes.
    public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
        return checkpointManager.checkpoint(update);
    }

    // ===== Polling =====

    // This method will poll the operation updates from the durable backend and return a future which completes
    // when an update of the operation is received.
    // This is useful for in-process waits. For example, we want to
    // wait while another thread is still running, and we therefore are not
    // re-invoked because we never suspended.
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId) {
        return checkpointManager.pollForUpdate(operationId);
    }

    /**
     * Pools for operation updates at a specific time
     *
     * @param operationId the operation id to poll for updates
     * @param at the time to poll for updates
     * @return a completable future that completes with the operation update
     */
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId, Instant at) {
        return checkpointManager.pollForUpdate(operationId, at);
    }

    // ===== Utilities =====
    /** Shutdown the checkpoint batcher. */
    @Override
    public void close() {
        validateRunningThreads();

        checkpointManager.shutdown();
    }

    private void validateRunningThreads() {
        // This will detect stuck user thread and thread leaks in the thread pool
        for (BaseDurableOperation op : registeredOperations.values()) {
            var userHandlerFuture = op.getRunningUserHandler();
            if (userHandlerFuture != null && !userHandlerFuture.isDone()) {
                // Some user threads can still be running because
                // the operations that run them have never been waiting for and the execution has completed.
                logger.info("Waiting for operation to complete before shutting down: {}", op.getOperationId());
                try {
                    userHandlerFuture.get();
                } catch (InterruptedException | CancellationException e) {
                    // if the user handler is stuck
                    throw new IllegalStateException(
                            "Stuck running user handler when shutting down: " + op.getOperationId());
                } catch (Exception e) {
                    // ok if the future completed exceptionally
                }
            }
        }

        // double check if the thread pool is empty
        if (durableConfig.getExecutorService() instanceof ThreadPoolExecutor threadPoolExecutor) {
            var threadCount = threadPoolExecutor.getActiveCount();
            // This may or may not be a problem because getActiveCount doesn't return an accurate number
            if (threadCount > 0) {
                logger.warn("{} active threads in user executor pool when shutting down", threadCount);
            }
        }
    }

    /** Returns {@code true} if the given status represents a terminal (final) operation state. */
    public static boolean isTerminalStatus(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED
                || status == OperationStatus.FAILED
                || status == OperationStatus.CANCELLED
                || status == OperationStatus.TIMED_OUT
                || status == OperationStatus.STOPPED;
    }

    /**
     * Terminates the execution immediately with an unrecoverable error.
     *
     * @param exception the unrecoverable exception that caused termination
     */
    public void terminateExecution(UnrecoverableDurableExecutionException exception) {
        stopAllOperations(exception);
        executionExceptionFuture.completeExceptionally(exception);
        throw exception;
    }

    /** Suspends the execution by completing the execution exception future with a {@link SuspendExecutionException}. */
    public void suspendExecution() {
        var ex = new SuspendExecutionException();
        stopAllOperations(ex);
        executionExceptionFuture.completeExceptionally(ex);
        throw ex;
    }

    /**
     * returns {@code true} if the execution is terminated exceptionally (with a {@link SuspendExecutionException} or an
     * unrecoverable error).
     */
    public boolean isExecutionCompletedExceptionally() {
        return executionExceptionFuture.isCompletedExceptionally();
    }

    private void stopAllOperations(Exception cause) {
        registeredOperations.values().forEach(op -> op.getCompletionFuture().completeExceptionally(cause));
    }

    /**
     * return a future that completes when userFuture completes successfully or the execution is terminated or
     * suspended.
     *
     * @param userFuture user provided function
     * @return a future of userFuture result if userFuture completes successfully, a user exception if userFuture
     *     completes with an exception, a SuspendExecutionException if the execution is suspended, or an
     *     UnrecoverableDurableExecutionException if the execution is terminated.
     */
    public <T> CompletableFuture<T> runUntilCompleteOrSuspend(CompletableFuture<T> userFuture) {
        return CompletableFuture.anyOf(userFuture, executionExceptionFuture).thenApply(v -> {
            // reaches here only if userFuture complete successfully
            if (userFuture.isDone()) {
                return userFuture.join();
            }
            return null;
        });
    }
}
