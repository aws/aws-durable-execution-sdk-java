// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.DurableConfig;
import com.amazonaws.lambda.durable.exception.UnrecoverableDurableExecutionException;
import com.amazonaws.lambda.durable.operation.BaseDurableOperation;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

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
    private final String executionOperationId;
    private final String durableExecutionArn;
    private final AtomicReference<ExecutionMode> executionMode;

    // ===== Thread Coordination =====
    private final Map<String, BaseDurableOperation<?>> registeredOperations =
            Collections.synchronizedMap(new HashMap<>());
    private final Map<String, ThreadType> activeThreads = Collections.synchronizedMap(new HashMap<>());
    private static final ThreadLocal<OperationContext> currentContext = new ThreadLocal<>();
    private final CompletableFuture<Void> executionExceptionFuture = new CompletableFuture<>();

    // ===== Checkpoint Batching =====
    private final CheckpointBatcher checkpointBatcher;

    public ExecutionManager(
            String durableExecutionArn,
            String checkpointToken,
            CheckpointUpdatedExecutionState initialExecutionState,
            DurableConfig config) {
        this.durableExecutionArn = durableExecutionArn;
        this.executionOperationId = initialExecutionState.operations().get(0).id();

        // Create checkpoint batcher for internal coordination
        this.checkpointBatcher =
                new CheckpointBatcher(config, durableExecutionArn, checkpointToken, this::onCheckpointComplete);

        this.operationStorage = checkpointBatcher.fetchAllPages(initialExecutionState).stream()
                .collect(Collectors.toConcurrentMap(Operation::id, op -> op));

        // Start in REPLAY mode if we have more than just the initial EXECUTION operation
        this.executionMode =
                new AtomicReference<>(operationStorage.size() > 1 ? ExecutionMode.REPLAY : ExecutionMode.EXECUTION);
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
        return operationStorage.get(executionOperationId);
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
    /**
     * Registers a thread as active without setting the thread local OperationContext. Use this when registration must
     * happen on a different thread than execution. Call setCurrentContext() on the execution thread to set the local
     * OperationContext.
     *
     * @see OperationContext
     */
    public void registerActiveThread(String threadId, ThreadType threadType) {
        if (activeThreads.containsKey(threadId)) {
            logger.trace("Thread '{}' ({}) already registered as active", threadId, threadType);
            return;
        }
        activeThreads.put(threadId, threadType);
        logger.trace(
                "Registered thread '{}' ({}) as active (no context). Active threads: {}",
                threadId,
                threadType,
                activeThreads.size());
    }

    /**
     * Sets the current thread's context. Use after registerActiveThreadWithoutContext() when the execution thread is
     * different from the registration thread.
     */
    public void setCurrentContext(String contextId, ThreadType threadType) {
        currentContext.set(new OperationContext(contextId, threadType));
    }

    /** Returns the current thread's context, or null if not set. */
    public OperationContext getCurrentContext() {
        return currentContext.get();
    }

    public void deregisterActiveThreadAndUnsetCurrentContext(String threadId) {
        // Skip if already suspended
        if (executionExceptionFuture.isDone()) {
            return;
        }

        if (!activeThreads.containsKey(threadId)) {
            logger.warn("Thread '{}' not active, cannot deregister", threadId);
            return;
        }

        ThreadType type = activeThreads.remove(threadId);
        currentContext.remove();
        logger.trace("Deregistered thread '{}' ({}). Active threads: {}", threadId, type, activeThreads.size());

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

    private boolean isTerminalStatus(OperationStatus status) {
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
