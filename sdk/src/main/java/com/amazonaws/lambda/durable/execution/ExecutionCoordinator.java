package com.amazonaws.lambda.durable.execution;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.checkpoint.SuspendExecutionException;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Coordinates thread lifecycle, phasers, and suspension for durable execution.
 * 
 * Manages:
 * - Active thread registration/deregistration
 * - Phaser creation and management
 * - Suspension logic when no threads are active
 * - Managed executor for step execution
 */
public class ExecutionCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionCoordinator.class);

    // Track active threads (conceptual, not physical)
    // Key: thread ID (e.g., "Root", "1-step")
    // Value: thread type (CONTEXT or STEP)
    private final Map<String, ThreadType> activeThreads = Collections.synchronizedMap(new HashMap<>());

    // Phasers for coordinating operation completion
    // Key: operation ID
    // Value: Phaser for that operation
    private final Map<String, Phaser> openPhasers = Collections.synchronizedMap(new HashMap<>());

    // Completed when execution should suspend
    // Used to signal polling threads to stop
    private final CompletableFuture<Void> suspendExecutionFuture = new CompletableFuture<>();

    // Managed executor for step execution
    private final Executor managedExecutor;

    // Reference to checkpoint manager for operations to use
    private final CheckpointManager checkpointManager;

    public ExecutionCoordinator(CheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;

        // Create managed executor with named threads
        this.managedExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("durable-step-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Register a thread as active.
     * 
     * @param threadId   Unique thread identifier (e.g., "Root", "1-step")
     * @param threadType Type of thread (CONTEXT or STEP)
     */
    public void registerActiveThread(String threadId, ThreadType threadType) {
        if (activeThreads.containsKey(threadId)) {
            logger.debug("Thread '{}' ({}) already registered as active", threadId, threadType);
            return;
        }

        synchronized (this) {
            activeThreads.put(threadId, threadType);
            logger.debug("Registered thread '{}' ({}) as active. Active threads: {}",
                    threadId, threadType, activeThreads.size());
        }
    }

    /**
     * Deregister a thread.
     * If no threads remain active, completes suspendExecutionFuture and throws
     * SuspendExecutionException.
     * 
     * @param threadId Thread identifier to deregister
     * @throws SuspendExecutionException if no active threads remain
     */
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
            logger.debug("Deregistered thread '{}' ({}). Active threads: {}",
                    threadId, type, activeThreads.size());

            if (activeThreads.isEmpty()) {
                logger.info("No active threads remaining - suspending execution");
                suspendExecutionFuture.complete(null);
                throw new SuspendExecutionException();
            }
        }
    }

    /**
     * Create and register a phaser for an operation.
     * 
     * @param operationId Operation identifier
     * @return Phaser with 1 registered party (the operation itself)
     */
    public Phaser startPhaser(String operationId) {
        Phaser phaser = new Phaser(1);
        openPhasers.put(operationId, phaser);
        logger.debug("Started phaser for operation '{}'", operationId);
        return phaser;
    }

    /**
     * Get the phaser for an operation.
     * 
     * @param operationId Operation identifier
     * @return Phaser for the operation, or null if not found
     */
    public Phaser getPhaser(String operationId) {
        return openPhasers.get(operationId);
    }

    /**
     * Get the managed executor for step execution.
     * 
     * @return Executor with named daemon threads
     */
    public Executor getManagedExecutor() {
        return managedExecutor;
    }

    /**
     * Get the suspend execution future.
     * Polling threads check this to know when to stop.
     * 
     * @return Future that completes when execution should suspend
     */
    public CompletableFuture<Void> getSuspendExecutionFuture() {
        return suspendExecutionFuture;
    }

    /**
     * Get the checkpoint manager.
     * 
     * @return CheckpointManager instance
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    /**
     * Send an operation update for checkpointing.
     * 
     * This accepts null for empty checkpoints (polling).
     * The checkpoint response will call updateOperations() to advance phasers.
     * 
     * @param update Operation update to send (null for empty checkpoint)
     * @return CompletableFuture that completes when checkpoint is done
     */
    public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
        // For now, delegate to checkpoint manager
        // Empty checkpoints (null) are handled by CheckpointManager
        return checkpointManager.checkpoint(update);
    }

    /**
     * Update operations from checkpoint response.
     * Advances phasers for completed operations.
     */
    public void updateOperations(java.util.List<Operation> newOperations) {
        for (Operation operation : newOperations) {
            // Notify anyone waiting on this operation
            if (openPhasers.containsKey(operation.id()) && isTerminalStatus(operation.status())) {
                Phaser phaser = openPhasers.get(operation.id());

                // Two-phase completion
                phaser.arriveAndAwaitAdvance(); // Phase 0 -> 1 (notify waiters)
                phaser.arriveAndAwaitAdvance(); // Phase 1 -> 2 (wait for reactivation)
            }
        }
    }

    private boolean isTerminalStatus(software.amazon.awssdk.services.lambda.model.OperationStatus status) {
        return status == software.amazon.awssdk.services.lambda.model.OperationStatus.SUCCEEDED ||
                status == software.amazon.awssdk.services.lambda.model.OperationStatus.FAILED ||
                status == software.amazon.awssdk.services.lambda.model.OperationStatus.CANCELLED ||
                status == software.amazon.awssdk.services.lambda.model.OperationStatus.TIMED_OUT ||
                status == software.amazon.awssdk.services.lambda.model.OperationStatus.STOPPED;
    }

    /**
     * Get an operation from the checkpoint manager.
     * 
     * @param operationId Operation identifier
     * @return Operation if found
     */
    public Operation getOperation(String operationId) {
        return checkpointManager.getOperation(operationId).orElse(null);
    }

    /**
     * Start polling for operation updates.
     * 
     * @param operationId   Operation being polled
     * @param phaser        Phaser to complete when operation is done
     * @param firstPollTime When to start polling
     * @param period        How often to poll
     */
    public void pollForUpdates(String operationId, Phaser phaser,
            java.time.Instant firstPollTime, Duration period) {
        managedExecutor.execute(() -> {
            // Sleep until the start (no-op if firstPollTime is in the past)
            try {
                Duration sleepDuration = Duration.between(java.time.Instant.now(), firstPollTime);
                if (!sleepDuration.isNegative()) {
                    logger.debug("Polling for '{}': sleeping {} before polling",
                            operationId, sleepDuration);
                    Thread.sleep(sleepDuration.toMillis());
                }
            } catch (InterruptedException ignored) {
                return;
            }

            // Poll while phaser is in phase 0 (operation not complete)
            while (phaser.getPhase() == 0) {
                if (suspendExecutionFuture.isDone()) {
                    // We suspended, so quietly give up. We don't need to poll, the backend will
                    // re-invoke us instead.
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

    /**
     * Start polling for operation updates (CompletableFuture version).
     * Used for retry polling when step is in PENDING status.
     * 
     * @param operationId   Operation being polled
     * @param future        Future to complete when operation is READY
     * @param firstPollTime When to start polling
     * @param period        How often to poll
     */
    public void pollForUpdates(String operationId, CompletableFuture<Void> future,
            java.time.Instant firstPollTime, Duration period) {
        managedExecutor.execute(() -> {
            // Sleep until first poll time
            try {
                Duration sleepDuration = Duration.between(java.time.Instant.now(), firstPollTime);
                if (!sleepDuration.isNegative()) {
                    logger.debug("Polling for '{}': sleeping {} before first poll",
                            operationId, sleepDuration);
                    Thread.sleep(sleepDuration.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Poll while future is not done
            while (!future.isDone()) {
                if (suspendExecutionFuture.isDone()) {
                    // Execution suspended, stop polling
                    logger.debug("Polling for '{}': execution suspended, stopping", operationId);
                    return;
                }

                logger.debug("Polling for '{}': sending empty checkpoint", operationId);
                // Send empty checkpoint to check for updates
                sendOperationUpdate(null).join();

                // Check if operation is now READY
                var op = getOperation(operationId);
                if (op != null && op.status() == software.amazon.awssdk.services.lambda.model.OperationStatus.READY) {
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
}
