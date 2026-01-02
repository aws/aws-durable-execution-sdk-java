// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.client;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionRequest;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateRequest;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

public class LambdaDurableFunctionsClient implements DurableExecutionClient {

    private static final Logger logger = LoggerFactory.getLogger(LambdaDurableFunctionsClient.class);

    private static final LambdaClient AWSLambdaSDKClient = LambdaClient.builder()
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .region(Region.of(System.getenv(SdkSystemSetting.AWS_REGION.environmentVariable())))
            .build();

    @Override
    public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        var request = CheckpointDurableExecutionRequest.builder()
                .durableExecutionArn(arn)
                .checkpointToken(token)
                .updates(updates)
                .build();
        logger.debug("Calling DAR backend with {} updates: {}", updates.size(), request);

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
