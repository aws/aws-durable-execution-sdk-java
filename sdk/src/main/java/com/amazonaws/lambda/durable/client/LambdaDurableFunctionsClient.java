package com.amazonaws.lambda.durable.client;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;

import java.net.URI;
import java.util.List;

public class LambdaDurableFunctionsClient implements DurableExecutionClient {

    private final LambdaClient AWSLambdaSDKClient = LambdaClient.builder()
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .region(Region.of(System.getenv(SdkSystemSetting.AWS_REGION.environmentVariable())))
            .build();;
    
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
