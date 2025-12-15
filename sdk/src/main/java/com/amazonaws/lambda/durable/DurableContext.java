package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.checkpoint.SuspendExecutionException;
import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class DurableContext {
    private final CheckpointManager checkpointManager;
    private final SerDes serDes;
    private final Context lambdaContext;
    private final AtomicInteger operationCounter;
    
    DurableContext(CheckpointManager checkpointManager, SerDes serDes, Context lambdaContext) {
        this.checkpointManager = checkpointManager;
        this.serDes = serDes;
        this.lambdaContext = lambdaContext;
        this.operationCounter = new AtomicInteger(0);
    }
    
    public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        var operationId = nextOperationId();
        
        // Check replay through checkpoint manager
        var existing = checkpointManager.getOperation(operationId);
        
        // Validate replay consistency
        if (existing.isPresent()) {
            validateReplay(operationId, OperationType.STEP, name, existing.get());
        }
        
        if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
            return serDes.deserialize(existing.get().stepDetails().result(), resultType);
        }

        var result = func.get();
        checkpoint(operationId, name, OperationType.STEP, OperationAction.SUCCEED, result);
        
        return result;
    }
    
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        var operationId = nextOperationId();
        
        // Check replay through checkpoint manager
        var existing = checkpointManager.getOperation(operationId);
        
        // Validate replay consistency
        if (existing.isPresent()) {
            validateReplay(operationId, OperationType.STEP, name, existing.get());
        }
        
        if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
            return new DurableFuture<>(CompletableFuture.completedFuture(
                serDes.deserialize(existing.get().stepDetails().result(), resultType)
            ));
        }
        
        // Execute async
        var future = CompletableFuture.supplyAsync(() -> {
            var result = func.get();
            checkpoint(operationId, name, OperationType.STEP, OperationAction.SUCCEED, result);
            return result;
        });
        
        return new DurableFuture<>(future);
    }
    
    public void wait(Duration duration) {
        wait(null, duration);
    }
    
    public void wait(String name, Duration duration) {
        var operationId = nextOperationId();
        
        // Check replay through checkpoint manager
        var existing = checkpointManager.getOperation(operationId);
        
        // Validate replay consistency
        if (existing.isPresent()) {
            validateReplay(operationId, OperationType.WAIT, name, existing.get());
        }
        
        if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
            return; // Wait already completed
        }

        checkpoint(operationId, name, OperationType.WAIT, OperationAction.START, null);
        throw new SuspendExecutionException();
    }
    
    public Context getLambdaContext() {
        return lambdaContext;
    }
    
    /**
     * Validates that current operation matches checkpointed operation during replay.
     */
    private void validateReplay(String operationId, OperationType expectedType, String expectedName, Operation checkpointed) {
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
    
    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }
    
    private void checkpoint(String operationId, String name, OperationType type, OperationAction action, Object result) {
        var update = OperationUpdate.builder()
                .id(operationId)
                .name(name)
                .type(type)
                .action(action)
                .payload(serDes.serialize(result))
                .build();

        checkpointManager.checkpoint(update).join(); //Todo: Currently blocked until checkpointed
    }
}
