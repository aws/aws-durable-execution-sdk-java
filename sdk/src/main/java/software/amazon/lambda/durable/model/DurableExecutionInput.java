// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;

/**
 * Input payload received by the Lambda handler from the Durable Functions backend.
 *
 * @param durableExecutionArn ARN identifying this durable execution
 * @param checkpointToken token used to authenticate checkpoint API calls
 * @param initialExecutionState snapshot of operations already completed in previous invocations
 */
public record DurableExecutionInput(
        String durableExecutionArn, String checkpointToken, CheckpointUpdatedExecutionState initialExecutionState) {}
