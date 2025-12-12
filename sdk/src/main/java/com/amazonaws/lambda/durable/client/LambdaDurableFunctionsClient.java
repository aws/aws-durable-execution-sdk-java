package com.amazonaws.lambda.durable.client;

import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import java.util.List;

public class LambdaDurableFunctionsClient implements DurableExecutionClient {

    private final LambdaClient AWSLambdaSDKClient;
    
    public LambdaDurableFunctionsClient(LambdaClient AWSLambdaSDKClient) {
        if (AWSLambdaSDKClient == null) {
            this.AWSLambdaSDKClient =  LambdaClient.create();
        } else {
            this.AWSLambdaSDKClient = AWSLambdaSDKClient;
        }
    }
    
    @Override
    public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        var request = CheckpointDurableExecutionRequest.builder()
            .durableExecutionArn(arn)
            .checkpointToken(token)
            .updates(updates)
            .build();
        
        return AWSLambdaSDKClient.checkpointDurableExecution(request);
    }
    
    @Override
    public GetDurableExecutionStateResponse getExecutionState(String arn, String marker) {
        var request = GetDurableExecutionStateRequest.builder()
            .durableExecutionArn(arn)
            .marker(marker)
            .build();
        
        return AWSLambdaSDKClient.getDurableExecutionState(request);
    }
}
