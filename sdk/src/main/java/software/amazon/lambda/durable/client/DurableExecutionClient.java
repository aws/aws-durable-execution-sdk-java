// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.client;

import java.util.List;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Client interface for communicating with the Lambda Durable Functions backend.
 *
 * <p>Provides checkpoint and state-retrieval operations used internally by the SDK.
 */
public interface DurableExecutionClient {

    /**
     * Sends a batch of operation updates to the backend.
     *
     * @param arn the durable execution ARN
     * @param token the checkpoint token
     * @param updates the operation updates to send
     * @return the checkpoint response
     */
    CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates);

    /**
     * Retrieves the current execution state from the backend.
     *
     * @param arn the durable execution ARN
     * @param checkpointToken the checkpoint token
     * @param marker pagination marker, or null for the first page
     * @return the execution state response
     */
    GetDurableExecutionStateResponse getExecutionState(String arn, String checkpointToken, String marker);
}
