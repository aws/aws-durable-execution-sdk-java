package com.amazonaws.lambda.durable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionCoordinator;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.operation.StepOperation;
import com.amazonaws.lambda.durable.operation.WaitOperation;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;

public class DurableContext {
    private final CheckpointManager checkpointManager;
    private final SerDes serDes;
    private final Context lambdaContext;
    private final AtomicInteger operationCounter;
    private final ExecutionCoordinator coordinator;

    DurableContext(CheckpointManager checkpointManager, SerDes serDes, Context lambdaContext,
            ExecutionCoordinator coordinator) {
        this.checkpointManager = checkpointManager;
        this.serDes = serDes;
        this.lambdaContext = lambdaContext;
        this.operationCounter = new AtomicInteger(0);
        this.coordinator = coordinator;

        // Register root context thread as active
        coordinator.registerActiveThread("Root", ThreadType.CONTEXT);
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        return step(name, resultType, func, null);
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, resultType, func, config).get();
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(name, resultType, func, null);
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        var operationId = nextOperationId();

        // Validate replay consistency
        var existing = checkpointManager.getOperation(operationId);
        if (existing.isPresent()) {
            validateReplay(operationId, OperationType.STEP, name, existing.get());
        }

        // Create phaser for this operation
        Phaser phaser = coordinator.startPhaser(operationId);

        // Create and start step operation
        StepOperation<T> operation = new StepOperation<>(
                operationId,
                name,
                func,
                resultType,
                config,
                phaser,
                coordinator,
                serDes);

        operation.execute(); // Start the step (returns immediately)

        return new DurableFuture<>(operation);
    }

    public void wait(Duration duration) {
        wait(null, duration);
    }

    public void wait(String waitName, Duration duration) {
        var operationId = nextOperationId();

        // Validate replay consistency
        var existing = checkpointManager.getOperation(operationId);
        if (existing.isPresent()) {
            validateReplay(operationId, OperationType.WAIT, waitName, existing.get());
        }

        // Create phaser for this operation
        Phaser phaser = coordinator.startPhaser(operationId);

        // Create and start wait operation
        WaitOperation operation = new WaitOperation(
                operationId,
                waitName,
                duration,
                phaser,
                coordinator);

        operation.execute(); // Checkpoint the wait
        operation.get(); // Block (will throw SuspendExecutionException if needed)
    }

    public Context getLambdaContext() {
        return lambdaContext;
    }

    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }

    /**
     * Validates that current operation matches checkpointed operation during
     * replay.
     */
    private void validateReplay(String operationId, OperationType expectedType, String expectedName,
            Operation checkpointed) {
        if (checkpointed == null || checkpointed.type() == null) {
            return; // First execution, no validation needed
        }

        if (!checkpointed.type().equals(expectedType)) {
            throw new NonDeterministicExecutionException(
                    String.format("Operation type mismatch for \"%s\". Expected %s, got %s",
                            operationId, checkpointed.type(), expectedType));
        }

        if (!Objects.equals(checkpointed.name(), expectedName)) {
            throw new NonDeterministicExecutionException(
                    String.format("Operation name mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                            operationId, checkpointed.name(), expectedName));
        }
    }

}
