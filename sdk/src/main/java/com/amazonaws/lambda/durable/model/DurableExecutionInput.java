package com.amazonaws.lambda.durable.model;

import com.amazonaws.lambda.durable.serde.AwsSdkOperationDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import software.amazon.awssdk.services.lambda.model.Operation;

import java.util.List;

public record DurableExecutionInput(
    String durableExecutionArn,
    String checkpointToken,
    InitialExecutionState initialExecutionState
) {
    public record InitialExecutionState(
        @JsonDeserialize(contentUsing = AwsSdkOperationDeserializer.class)
        List<Operation> operations,
        String nextMarker
    ) {}
}
