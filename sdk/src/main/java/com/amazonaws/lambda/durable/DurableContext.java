package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.checkpoint.SuspendExecutionException;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

import java.time.Duration;
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
        if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
            return serDes.deserialize(existing.get().stepDetails().result(), resultType);
        }
        
        // Execute
        var result = func.get();
        
        // Checkpoint
        checkpoint(operationId, OperationType.STEP, OperationAction.SUCCEED, result);
        
        return result;
    }
    
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        var operationId = nextOperationId();
        
        // Check replay through checkpoint manager
        var existing = checkpointManager.getOperation(operationId);
        if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
            return new DurableFuture<>(CompletableFuture.completedFuture(
                serDes.deserialize(existing.get().stepDetails().result(), resultType)
            ));
        }
        
        // Execute async
        var future = CompletableFuture.supplyAsync(() -> {
            var result = func.get();
            checkpoint(operationId, OperationType.STEP, OperationAction.SUCCEED, result);
            return result;
        });
        
        return new DurableFuture<>(future);
    }
    
    public void wait(Duration duration) {
        var operationId = nextOperationId();
        
        // Check replay through checkpoint manager
        var existing = checkpointManager.getOperation(operationId);
        if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
            return; // Wait already completed
        }
        
        // Checkpoint START with null payload
        // Note: Real AWS SDK will use WaitOptions with WaitSeconds
        // For Level 1 with mock types, we just checkpoint the operation
        checkpoint(operationId, OperationType.WAIT, OperationAction.START, null);
        throw new SuspendExecutionException();
    }
    
    public Context getLambdaContext() {
        return lambdaContext;
    }
    
    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }
    
    private void checkpoint(String operationId, OperationType type, OperationAction action, Object result) {
        var update = OperationUpdate.builder()
                .id(operationId)
                .type(type)
                .action(action)
                .payload(serDes.serialize(result))
                .build();

        checkpointManager.checkpoint(update).join(); //Todo: Currently blocked until checkpointed
    }
}
