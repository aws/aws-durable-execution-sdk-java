// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.exception.UnrecoverableDurableExecutionException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Phaser;
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
 *   <li>Phaser management (coordination)
 *   <li>Checkpoint batching (via CheckpointManager)
 *   <li>Polling (for waits and retries)
 * </ul>
 *
 * <p>This is the single entry point for all execution coordination. Internal coordination (polling, checkpointing) uses
 * a dedicated SDK thread pool, while user-defined operations run on a customer-configured executor.
 *
 * @see InternalExecutor
 */
public class ExecutionManager {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionManager.class);

    // ===== Execution State =====
    private final Map<String, Operation> operations;
    private final String executionOperationId;
    private final String durableExecutionArn;
    private final AtomicReference<ExecutionMode> executionMode;

    // ===== Thread Coordination =====
    private final Map<String, ThreadType> activeThreads = Collections.synchronizedMap(new HashMap<>());
    private static final ThreadLocal<OperationContext> currentContext = new ThreadLocal<>();
    private final Map<String, Phaser> openPhasers = Collections.synchronizedMap(new HashMap<>());
    private final CompletableFuture<Void> executionExceptionFuture = new CompletableFuture<>();

    // ===== Checkpoint Batching =====
    private final CheckpointBatcher checkpointBatcher;

    public ExecutionManager(
            String durableExecutionArn,
            String checkpointToken,
            CheckpointUpdatedExecutionState initialExecutionState,
            DurableExecutionClient client) {
        this.durableExecutionArn = durableExecutionArn;
        this.executionOperationId = initialExecutionState.operations().get(0).id();

        // Create checkpoint batcher for internal coordination
        this.checkpointBatcher =
                new CheckpointBatcher(client, durableExecutionArn, checkpointToken, this::onCheckpointComplete);

        this.operations = checkpointBatcher.fetchAllPages(initialExecutionState).stream()
                .collect(Collectors.toConcurrentMap(Operation::id, op -> op));

        // Start in REPLAY mode if we have more than just the initial EXECUTION operation
        this.executionMode =
                new AtomicReference<>(operations.size() > 1 ? ExecutionMode.REPLAY : ExecutionMode.EXECUTION);
    }

    // ===== State Management =====

    public String getDurableExecutionArn() {
        return durableExecutionArn;
    }

    public boolean isReplaying() {
        return executionMode.get() == ExecutionMode.REPLAY;
    }

    // ===== Checkpoint Completion Handler =====
    /** Called by CheckpointManager when a checkpoint completes. Updates state and advances phasers. */
    private void onCheckpointComplete(List<Operation> newOperations) {
        // Update operation storage
        newOperations.forEach(op -> operations.put(op.id(), op));

        // Advance phasers for completed operations
        for (Operation operation : newOperations) {
            if (openPhasers.containsKey(operation.id()) && isTerminalStatus(operation.status())) {
                var phaser = openPhasers.get(operation.id());

                // Two-phase completion
                logger.trace("Advancing phaser 0 -> 1: {}", phaser);
                phaser.arriveAndAwaitAdvance();
                logger.trace("Advancing phaser 1 -> 2: {}", phaser);
                phaser.arriveAndAwaitAdvance();
            }
        }
    }

    /**
     * Gets an operation by ID and updates replay state. Transitions from REPLAY to EXECUTION mode if the operation is
     * not found or is not in a terminal state (still in progress).
     *
     * @param operationId the operation ID to get
     * @return the existing operation, or null if not found (first execution)
     */
    public Operation getOperationAndUpdateReplayState(String operationId) {
        var existing = operations.get(operationId);
        if (executionMode.get() == ExecutionMode.REPLAY) {
            if (existing == null || !isTerminalStatus(existing.status())) {
                if (executionMode.compareAndSet(ExecutionMode.REPLAY, ExecutionMode.EXECUTION)) {
                    logger.debug("Transitioned to EXECUTION mode at operation '{}'", operationId);
                }
            }
        }
        return existing;
    }

    public Operation getExecutionOperation() {
        return operations.get(executionOperationId);
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

    // ===== Phaser Management =====

    public Phaser startPhaser(String operationId) {
        var phaser = new Phaser(1);
        openPhasers.put(operationId, phaser);
        logger.trace("Started phaser for operation '{}'", operationId);
        return phaser;
    }

    public Phaser getPhaser(String operationId) {
        return openPhasers.get(operationId);
    }

    // ===== Checkpointing =====

    public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
        return checkpointBatcher.checkpoint(update);
    }

    // ===== Polling =====

    // This method will poll the operation from the DAR backend until it moves to a
    // terminal state. This is useful for in-process waits. For example, we want to
    // wait while another thread is still running and we therefore are not
    // re-invoked because we never suspended.
    public void pollForOperationUpdates(String operationId, Instant firstPollTime, Duration period) {
        InternalExecutor.INSTANCE.execute(() -> {
            // Sleep until the start
            try {
                var sleepDuration = Duration.between(Instant.now(), firstPollTime);
                if (!sleepDuration.isNegative()) {
                    logger.debug("Polling for '{}': sleeping {} before polling", operationId, sleepDuration);
                    Thread.sleep(sleepDuration.toMillis());
                }
            } catch (InterruptedException ignored) {
            }

            // Poll while phaser is in phase 0
            while (this.getPhaser(operationId).getPhase() == ExecutionPhase.RUNNING.getValue()) {
                if (executionExceptionFuture.isDone()) {
                    logger.debug("Polling for '{}': execution suspended, stopping", operationId);
                    return;
                }

                logger.debug("Polling for '{}': doing a poll", operationId);
                sendOperationUpdate(null).join();

                try {
                    Thread.sleep(period.toMillis());
                } catch (InterruptedException ignored) {
                    return;
                }
            }

            logger.debug("Polling for '{}': done polling", operationId);
        });
    }

    // This method will poll the operation from the DAR backend until we move to a
    // READY state. If we are ready (based on NextScheduleTimestamp),
    // the provided future will be completed. This is useful for in-process retries.
    // For example, we
    // want to retry while another thread is still running and we therefore are not
    // re-invoked because we never suspended.
    public void pollUntilReady(
            String operationId, CompletableFuture<Void> future, Instant firstPollTime, Duration period) {
        InternalExecutor.INSTANCE.execute(() -> {
            // Sleep until first poll time
            try {
                Duration sleepDuration = Duration.between(Instant.now(), firstPollTime);
                if (!sleepDuration.isNegative()) {
                    logger.debug("Polling for '{}': sleeping {} before first poll", operationId, sleepDuration);
                    Thread.sleep(sleepDuration.toMillis());
                }
            } catch (InterruptedException e) {
            }

            // Poll while future is not done
            while (!future.isDone()) {
                if (executionExceptionFuture.isDone()) {
                    logger.debug("Polling for '{}': execution suspended, stopping", operationId);
                    return;
                }

                logger.debug("Polling for '{}': sending empty checkpoint", operationId);
                sendOperationUpdate(null).join();

                // Check if operation is now READY
                var op = getOperationAndUpdateReplayState(operationId);
                if (op != null && op.status() == OperationStatus.READY) {
                    future.complete(null);
                    break;
                }

                try {
                    Thread.sleep(period.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            logger.debug("Polling for '{}': future complete", operationId);
        });
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
