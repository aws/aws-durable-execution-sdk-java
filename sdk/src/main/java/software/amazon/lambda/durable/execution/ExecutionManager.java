// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

/**
 * Central manager for durable execution coordination.
 *
 * <p>Consolidates:
 *
 * <ul>
 *   <li>Execution state (operations, checkpoint token)
 *   <li>Thread lifecycle (registration/deregistration)
 *   <li>Checkpoint batching (via CheckpointBatcher)
 *   <li>Checkpoint result handling (CheckpointBatcher callback)
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
public class ExecutionManager {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionManager.class);

    // ===== Execution State =====
    private final Map<String, Operation> operationStorage;
    private final Operation executionOp;
    private final String durableExecutionArn;
    private final AtomicReference<ExecutionMode> executionMode;

    // ===== Thread Coordination =====
    private final Map<String, BaseDurableOperation<?>> registeredOperations =
            Collections.synchronizedMap(new HashMap<>());
    private final Set<String> activeThreads = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<ThreadContext> currentThreadContext = new ThreadLocal<>();
    private final CompletableFuture<Void> executionExceptionFuture = new CompletableFuture<>();

    // ===== Checkpoint Batching =====
    private final CheckpointBatcher checkpointBatcher;

    public ExecutionManager(
            String durableExecutionArn,
            String checkpointToken,
            CheckpointUpdatedExecutionState initialExecutionState,
            DurableConfig config) {
        this.durableExecutionArn = durableExecutionArn;

        // Create checkpoint batcher for internal coordination
        this.checkpointBatcher =
                new CheckpointBatcher(config, durableExecutionArn, checkpointToken, this::onCheckpointComplete);

        this.operationStorage = checkpointBatcher.fetchAllPages(initialExecutionState).stream()
                .collect(Collectors.toConcurrentMap(Operation::id, op -> op));

        // Start in REPLAY mode if we have more than just the initial EXECUTION operation
        this.executionMode =
                new AtomicReference<>(operationStorage.size() > 1 ? ExecutionMode.REPLAY : ExecutionMode.EXECUTION);

        executionOp = findExecutionOp(initialExecutionState);

        // Validate initial operation is an EXECUTION operation
        if (executionOp == null) {
            throw new IllegalStateException("First operation must be EXECUTION");
        }
        logger.debug("DurableExecution.execute() called");
        logger.debug("DurableExecutionArn: {}", durableExecutionArn);
        logger.debug("Initial operations count: {}", operationStorage.size());
        logger.debug("EXECUTION operation found: {}", executionOp.id());
    }

    // ===== State Management =====

    public String getDurableExecutionArn() {
        return durableExecutionArn;
    }

    public boolean isReplaying() {
        return executionMode.get() == ExecutionMode.REPLAY;
    }

    public void registerOperation(BaseDurableOperation<?> operation) {
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

    public Operation getExecutionOperation() {
        return executionOp;
    }

    private Operation findExecutionOp(CheckpointUpdatedExecutionState initialExecutionState) {
        // find execution OP in the input
        if (initialExecutionState != null
                && initialExecutionState.operations() != null
                && !initialExecutionState.operations().isEmpty()) {
            var op = initialExecutionState.operations().get(0);
            if (op.type() != OperationType.EXECUTION) {
                throw new IllegalStateException("First operation must be EXECUTION");
            }
            return op;
        }
        // find execution OP in the checkpoint result
        for (Operation op : operationStorage.values()) {
            if (op.type() == OperationType.EXECUTION) {
                return op;
            }
        }
        return null;
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
        if (activeThreads.contains(threadId)) {
            logger.trace("Thread '{}' already registered as active", threadId);
            return;
        }
        activeThreads.add(threadId);
        logger.trace("Registered thread '{}' as active. Active threads: {}", threadId, activeThreads.size());
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

        boolean removed = activeThreads.remove(threadId);
        if (removed) {
            logger.trace("Deregistered thread '{}' Active threads: {}", threadId, activeThreads.size());
        } else {
            logger.warn("Thread '{}' not active, cannot deregister", threadId);
        }

        if (activeThreads.isEmpty()) {
            logger.info("No active threads remaining - suspending execution");
            suspendExecution();
        }
    }

    // ===== Checkpointing =====

    // This method will checkpoint the operation updates to the durable backend and return a future which completes
    // when the checkpoint completes.
    public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
        return checkpointBatcher.checkpoint(update);
    }

    // ===== Polling =====

    // This method will poll the operation updates from the durable backend and return a future which completes
    // when an update of the operation is received.
    // This is useful for in-process waits. For example, we want to
    // wait while another thread is still running, and we therefore are not
    // re-invoked because we never suspended.
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId) {
        return checkpointBatcher.pollForUpdate(operationId);
    }

    public CompletableFuture<Operation> pollForOperationUpdates(String operationId, Duration delay) {
        return checkpointBatcher.pollForUpdate(operationId, delay);
    }

    // ===== Utilities =====
    public void shutdown() {
        checkpointBatcher.shutdown();
    }

    public static boolean isTerminalStatus(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED
                || status == OperationStatus.FAILED
                || status == OperationStatus.CANCELLED
                || status == OperationStatus.TIMED_OUT
                || status == OperationStatus.STOPPED;
    }

    public void terminateExecution(UnrecoverableDurableExecutionException exception) {
        executionExceptionFuture.completeExceptionally(exception);
        throw exception;
    }

    public void suspendExecution() {
        var ex = new SuspendExecutionException();
        executionExceptionFuture.completeExceptionally(ex);
        throw ex;
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
