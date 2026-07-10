// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.util.Map;

/**
 * Information provided when a checkpoint response changes one or more operations.
 *
 * <p>Holds a map with an {@link OperationChangeItemInfo} for every operation that changed in the checkpoint response.
 *
 * @param requestId the Lambda request ID for the invocation that observed the change
 * @param durableExecutionArn the durable execution ARN
 * @param updatedOperations operations whose status changed in this checkpoint response, keyed by operation ID
 * @param operations a snapshot of all known operations after the update, keyed by operation ID
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record OperationChangeInfo(
        String requestId,
        String durableExecutionArn,
        Map<String, OperationChangeItemInfo> updatedOperations,
        Map<String, OperationChangeItemInfo> operations) {}
