package com.amazonaws.lambda.durable.client;

import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

import java.util.List;

public interface DurableExecutionClient {
    CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates);
    GetDurableExecutionStateResponse getExecutionState(String arn, String marker);
}
