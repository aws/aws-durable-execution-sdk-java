package com.amazonaws.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.Operation;

import java.util.List;

public record DurableExecutionInput(
    String durableExecutionArn,
    String checkpointToken,
    InitialExecutionState initialExecutionState
) {
    public record InitialExecutionState(
        List<Operation> operations,
        String nextMarker
    ) {}
}
