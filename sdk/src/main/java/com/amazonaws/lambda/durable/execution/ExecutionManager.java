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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Central manager for durable execution coordination.
 *
 * <p>Consolidates: - Execution state (operations, checkpoint token) - Thread lifecycle (registration/deregistration) -
 * Phaser management (coordination) - Checkpoint batching (via CheckpointManager) - Polling (for waits and retries)
 *
 * <p>This is the single entry point for all execution coordination.
 */
public class ExecutionManager {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionManager.class);

    // ===== Execution State =====
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final String executionOperationId;
    private volatile String checkpointToken;
    private final String durableExecutionArn;

    // ===== Thread Coordination =====
    private final Map<String, ThreadType> activeThreads = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Phaser> openPhasers = Collections.synchronizedMap(new HashMap<>());
    private final CompletableFuture<Void> suspendExecutionFuture = new CompletableFuture<>();

    // ===== Executors =====
    private final Executor managedExecutor;

    // ===== Checkpoint Batching =====
    private final CheckpointBatcher checkpointBatcher;
    private final DurableExecutionClient client;

    public ExecutionManager(
            String durableExecutionArn,
            String checkpointToken,
            InitialExecutionState initialExecutionState,
            DurableExecutionClient client,
            Executor executor) {
        this.durableExecutionArn = durableExecutionArn;
        this.checkpointToken = checkpointToken;
        this.client = client;
        this.executionOperationId = initialExecutionState.operations().get(0).id();
        loadAllOperations(initialExecutionState);

        this.managedExecutor = executor;

        // Create checkpoint manager (package-private)
        // Pass method references to avoid cyclic dependency
        var checkpointExecutor = Executors.newSingleThreadExecutor();
        this.checkpointBatcher = new CheckpointBatcher(
                client, checkpointExecutor, durableExecutionArn, this::getCheckpointToken, this::onCheckpointComplete);
    }

    private void loadAllOperations(InitialExecutionState initialExecutionState) {
        var initialOperations = initialExecutionState.operations();
        initialOperations.forEach(op -> operations.put(op.id(), op));

        var nextMarker = initialExecutionState.nextMarker();
        while (nextMarker != null && !nextMarker.isEmpty()) {
            var response = client.getExecutionState(durableExecutionArn, nextMarker);
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
                logger.debug("Advancing phaser 0 -> 1: {}", phaser);
                phaser.arriveAndAwaitAdvance();
                logger.debug("Advancing phaser 1 -> 2: {}", phaser);
                phaser.arriveAndAwaitAdvance();
            }
        }
    }

    public Operation getOperation(String operationId) {
        return operations.get(operationId);
    }

    public Operation getExecutionOperation() {
        return operations.get(executionOperationId);
    }

    // ===== Thread Coordination =====

    public void registerActiveThread(String threadId, ThreadType threadType) {
        if (activeThreads.containsKey(threadId)) {
            logger.debug("Thread '{}' ({}) already registered as active", threadId, threadType);
            return;
        }

        synchronized (this) {
            activeThreads.put(threadId, threadType);
            logger.debug(
                    "Registered thread '{}' ({}) as active. Active threads: {}",
                    threadId,
                    threadType,
                    activeThreads.size());
        }
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

        synchronized (this) {
            ThreadType type = activeThreads.remove(threadId);
            logger.debug("Deregistered thread '{}' ({}). Active threads: {}", threadId, type, activeThreads.size());

            if (activeThreads.isEmpty()) {
                logger.info("No active threads remaining - suspending execution");
                suspendExecutionFuture.complete(null);
                throw new SuspendExecutionException();
            }
        }
    }

    // ===== Phaser Management =====

    public Phaser startPhaser(String operationId) {
        var phaser = new Phaser(1);
        openPhasers.put(operationId, phaser);
        logger.debug("Started phaser for operation '{}'", operationId);
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
        managedExecutor.execute(() -> {
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
        managedExecutor.execute(() -> {
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

    public Executor getManagedExecutor() {
        return managedExecutor;
    }

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
