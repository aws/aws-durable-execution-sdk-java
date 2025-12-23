// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.client;

import java.util.List;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

public interface DurableExecutionClient {
    CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates);

    GetDurableExecutionStateResponse getExecutionState(String arn, String marker);
}
