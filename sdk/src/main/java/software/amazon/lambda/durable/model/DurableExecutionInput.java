// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.List;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;

/**
 * Input payload received by the Lambda handler from the Durable Functions backend.
 *
 * @param durableExecutionArn ARN identifying this durable execution
 * @param checkpointToken token used to authenticate checkpoint API calls
 * @param initialExecutionState snapshot of operations already completed in previous invocations
 * @param updatedOperationIds IDs of operations that changed since the previous successful invocation; empty list if
 *     nothing changed
 * @param invocationSource whether this execution was invoked directly or by a chained invoke
 */
public record DurableExecutionInput(
        String durableExecutionArn,
        String checkpointToken,
        CheckpointUpdatedExecutionState initialExecutionState,
        List<String> updatedOperationIds,
        InvocationSource invocationSource) {

    public DurableExecutionInput {
        invocationSource = invocationSource == null ? InvocationSource.DIRECT : invocationSource;
    }

    /** Constructor that defaults invocation source to direct execution. */
    public DurableExecutionInput(
            String durableExecutionArn,
            String checkpointToken,
            CheckpointUpdatedExecutionState initialExecutionState,
            List<String> updatedOperationIds) {
        this(durableExecutionArn, checkpointToken, initialExecutionState, updatedOperationIds, InvocationSource.DIRECT);
    }

    /**
     * Constructor that defaults updatedOperationIds to empty list. Used by tests that don't need to supply updated
     * operation IDs.
     */
    public DurableExecutionInput(
            String durableExecutionArn, String checkpointToken, CheckpointUpdatedExecutionState initialExecutionState) {
        this(durableExecutionArn, checkpointToken, initialExecutionState, List.of(), InvocationSource.DIRECT);
    }
}
