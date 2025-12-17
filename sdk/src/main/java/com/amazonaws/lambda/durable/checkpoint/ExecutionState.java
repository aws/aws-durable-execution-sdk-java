package com.amazonaws.lambda.durable.checkpoint;

import software.amazon.awssdk.services.lambda.model.Operation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure state holder for durable execution state.
 * Contains only data and simple getters/setters - no business logic.
 */
public class ExecutionState {
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private volatile String checkpointToken;
    private final String durableExecutionArn;

    public ExecutionState(String durableExecutionArn, String checkpointToken, List<Operation> initialOperations) {
        this.durableExecutionArn = durableExecutionArn;
        this.checkpointToken = checkpointToken;
        initialOperations.forEach(op -> operations.put(op.id(), op));
    }

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
        newOperations.forEach(op -> operations.put(op.id(), op));
    }

    public Optional<Operation> getOperation(String operationId) {
        return Optional.ofNullable(operations.get(operationId));
    }

    public Collection<Operation> getAllOperations() {
        return operations.values();
    }
}
