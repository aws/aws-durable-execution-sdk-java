// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.client;

import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionRequest;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateRequest;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

public class LambdaDurableFunctionsClient implements DurableExecutionClient {

    private final LambdaClient lambdaClient;

    /**
     * Creates a LambdaDurableFunctionsClient with the provided LambdaClient.
     *
     * @param lambdaClient LambdaClient instance to use for backend communication
     * @throws NullPointerException if lambdaClient is null
     */
    public LambdaDurableFunctionsClient(LambdaClient lambdaClient) {
        this.lambdaClient = Objects.requireNonNull(lambdaClient, "LambdaClient cannot be null");
    }

    @Override
    public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        var request = CheckpointDurableExecutionRequest.builder()
                .durableExecutionArn(arn)
                .checkpointToken(token)
                .updates(updates)
                .build();

        return lambdaClient.checkpointDurableExecution(request);
    }

    @Override
    public GetDurableExecutionStateResponse getExecutionState(String arn, String checkpointToken, String marker) {
        var request = GetDurableExecutionStateRequest.builder()
                .durableExecutionArn(arn)
                .checkpointToken(checkpointToken)
                .marker(marker)
                .build();

        return lambdaClient.getDurableExecutionState(request);
    }
}
