package com.amazonaws.lambda.durable.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
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

import com.amazonaws.lambda.durable.client.DurableExecutionClient;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Central manager for durable execution coordination.
 * 
 * Consolidates:
 * - Execution state (operations, checkpoint token)
 * - Thread lifecycle (registration/deregistration)
 * - Phaser management (coordination)
 * - Checkpoint batching (via CheckpointManager)
 * - Polling (for waits and retries)
 * 
 * This is the single entry point for all execution coordination.
 */
public class ExecutionManager {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionManager.class);

    // ===== Execution State =====
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private volatile String checkpointToken;
    private final String durableExecutionArn;

    // ===== Thread Coordination =====
    private final Map<String, ThreadType> activeThreads = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Phaser> openPhasers = Collections.synchronizedMap(new HashMap<>());
    private final CompletableFuture<Void> suspendExecutionFuture = new CompletableFuture<>();

    // ===== Executors =====
    private final Executor managedExecutor;

    // ===== Checkpoint Batching =====
    private final CheckpointManager checkpointManager;

    public ExecutionManager(String durableExecutionArn, String checkpointToken,
            List<Operation> initialOperations,
            DurableExecutionClient client,
            Executor executor) {
        this.durableExecutionArn = durableExecutionArn;
        this.checkpointToken = checkpointToken;
        initialOperations.forEach(op -> operations.put(op.id(), op));

        // Use the provided executor (same one used for handler)
        this.managedExecutor = executor;

        // Create checkpoint manager (package-private)
        var checkpointExecutor = Executors.newSingleThreadExecutor();
        this.checkpointManager = new CheckpointManager(this, client, checkpointExecutor);
    }

    // ===== State Management =====

    public String getDurableExecutionArn() {
        return durableExecutionArn;
    }

    public String getCheckpointToken() {
        return checkpointToken;
    }

    public void updateCheckpointToken(String newToken) {
        this.checkpointToken = newToken;
    }

    public void updateOperations(List<Operation> newOperations) {
        // Update operation storage
        newOperations.forEach(op -> operations.put(op.id(), op));

        // Advance phasers for completed operations
        for (Operation operation : newOperations) {
            if (openPhasers.containsKey(operation.id()) && isTerminalStatus(operation.status())) {
                Phaser phaser = openPhasers.get(operation.id());

                // Two-phase completion
                phaser.arriveAndAwaitAdvance(); // Phase 0 -> 1 (notify waiters)
                phaser.arriveAndAwaitAdvance(); // Phase 1 -> 2 (wait for reactivation)
            }
        }
    }

    public Operation getOperation(String operationId) {
        return operations.get(operationId);
    }

    public Collection<Operation> getAllOperations() {
        return operations.values();
    }

    // ===== Thread Coordination =====

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

    // ===== Phaser Management =====

    public Phaser startPhaser(String operationId) {
        Phaser phaser = new Phaser(1);
        openPhasers.put(operationId, phaser);
        logger.debug("Started phaser for operation '{}'", operationId);
        return phaser;
    }

    public Phaser getPhaser(String operationId) {
        return openPhasers.get(operationId);
    }

    // ===== Checkpointing =====

    public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
        return checkpointManager.checkpoint(update);
    }

    // ===== Polling =====

    public void pollForUpdates(String operationId, Instant firstPollTime, Duration period) {
        managedExecutor.execute(() -> {
            // Sleep until the start
            try {
                Duration sleepDuration = Duration.between(Instant.now(), firstPollTime);
                if (!sleepDuration.isNegative()) {
                    logger.debug("Polling for '{}': sleeping {} before polling",
                            operationId, sleepDuration);
                    Thread.sleep(sleepDuration.toMillis());
                }
            } catch (InterruptedException ignored) {
                return;
            }

            // Poll while phaser is in phase 0
            while (this.getPhaser(operationId).getPhase() == 0) {
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

    public void pollForUpdates(String operationId, CompletableFuture<Void> future, Instant firstPollTime,
            Duration period) {
        managedExecutor.execute(() -> {
            // Sleep until first poll time
            try {
                Duration sleepDuration = Duration.between(Instant.now(), firstPollTime);
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
        checkpointManager.shutdown();
    }

    private boolean isTerminalStatus(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED ||
                status == OperationStatus.FAILED ||
                status == OperationStatus.CANCELLED ||
                status == OperationStatus.TIMED_OUT ||
                status == OperationStatus.STOPPED;
    }
}
