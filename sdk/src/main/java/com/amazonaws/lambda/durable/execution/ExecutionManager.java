// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final String executionOperationId;
    private volatile String checkpointToken;
    private final String durableExecutionArn;
    private final AtomicReference<ExecutionMode> executionMode;

    // ===== Thread Coordination =====
    private final Map<String, ThreadType> activeThreads = Collections.synchronizedMap(new HashMap<>());
    private static final ThreadLocal<OperationContext> currentContext = new ThreadLocal<>();
    private final Map<String, Phaser> openPhasers = Collections.synchronizedMap(new HashMap<>());
    private final CompletableFuture<Void> suspendExecutionFuture = new CompletableFuture<>();

    // ===== Checkpoint Batching =====
    private final CheckpointBatcher checkpointBatcher;
    private final DurableExecutionClient client;

    public ExecutionManager(
            String durableExecutionArn,
            String checkpointToken,
            InitialExecutionState initialExecutionState,
            DurableExecutionClient client) {
        this.durableExecutionArn = durableExecutionArn;
        this.checkpointToken = checkpointToken;
        this.client = client;
        this.executionOperationId = initialExecutionState.operations().get(0).id();
        loadAllOperations(initialExecutionState);

        // Start in REPLAY mode if we have more than just the initial EXECUTION operation
        this.executionMode =
                new AtomicReference<>(operations.size() > 1 ? ExecutionMode.REPLAY : ExecutionMode.EXECUTION);

        // Create checkpoint manager using common pool for internal coordination
        this.checkpointBatcher = new CheckpointBatcher(
                client, durableExecutionArn, this::getCheckpointToken, this::onCheckpointComplete);
    }

    private void loadAllOperations(InitialExecutionState initialExecutionState) {
        var initialOperations = initialExecutionState.operations();
        initialOperations.forEach(op -> operations.put(op.id(), op));

        var nextMarker = initialExecutionState.nextMarker();
        while (nextMarker != null && !nextMarker.isEmpty()) {
            var response = client.getExecutionState(durableExecutionArn, checkpointToken, nextMarker);
            response.operations().forEach(op -> operations.put(op.id(), op));
            nextMarker = response.nextMarker();
        }
    }

    // ===== Checkpoint Completion Handler =====

    /** Called by CheckpointManager when a checkpoint completes. Updates state and advances phasers. */
    private void onCheckpointComplete(String newToken, List<Operation> newOperations) {
        this.checkpointToken = newToken;
        updateOperations(newOperations);
    }

    // ===== State Management =====

    public String getDurableExecutionArn() {
        return durableExecutionArn;
    }

    public boolean isReplaying() {
        return executionMode.get() == ExecutionMode.REPLAY;
    }

    public String getCheckpointToken() {
        return checkpointToken;
    }

    private void updateOperations(List<Operation> newOperations) {
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

    public Operation getOperation(String operationId) {
        return operations.get(operationId);
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
        if (existing == null || !isTerminalStatus(existing.status())) {
            if (executionMode.compareAndSet(ExecutionMode.REPLAY, ExecutionMode.EXECUTION)) {
                logger.debug("Transitioned to EXECUTION mode at operation '{}'", operationId);
            }
        }
        return existing;
    }

    public Operation getExecutionOperation() {
        return operations.get(executionOperationId);
    }

    // ===== Thread Coordination =====

    public void registerActiveThreadWithContext(String threadId, ThreadType threadType) {
        if (activeThreads.containsKey(threadId)) {
            logger.trace("Thread '{}' ({}) already registered as active", threadId, threadType);
            return;
        }
        activeThreads.put(threadId, threadType);
        currentContext.set(new OperationContext(threadId, threadType));
        logger.trace(
                "Registered thread '{}' ({}) as active. Active threads: {}",
                threadId,
                threadType,
                activeThreads.size());
    }

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

    public void deregisterActiveThread(String threadId) {
        // Skip if already suspended
        if (suspendExecutionFuture.isDone()) {
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
            suspendExecutionFuture.complete(null);
            throw new SuspendExecutionException();
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
                if (suspendExecutionFuture.isDone()) {
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
                if (suspendExecutionFuture.isDone()) {
                    logger.debug("Polling for '{}': execution suspended, stopping", operationId);
                    return;
                }

                logger.debug("Polling for '{}': sending empty checkpoint", operationId);
                sendOperationUpdate(null).join();

                // Check if operation is now READY
                var op = getOperation(operationId);
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

    public CompletableFuture<Void> getSuspendExecutionFuture() {
        return suspendExecutionFuture;
    }

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
}
