package com.amazonaws.lambda.durable.execution;

import software.amazon.awssdk.services.lambda.model.Operation;

import java.util.List;

/**
 * Functional interface for checkpoint completion notifications.
 * 
 * CheckpointManager calls this when a checkpoint completes, allowing
 * ExecutionManager to update state and advance phasers.
 */
@FunctionalInterface
interface CheckpointCallback {
    
    /**
     * Called when a checkpoint completes successfully.
     * 
     * @param newToken New checkpoint token from backend
     * @param newOperations Updated operations from backend
     */
    void onComplete(String newToken, List<Operation> newOperations);
}
