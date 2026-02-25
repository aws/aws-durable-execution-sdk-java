// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;

public record DurableExecutionInput(
        String durableExecutionArn, String checkpointToken, CheckpointUpdatedExecutionState initialExecutionState) {}
