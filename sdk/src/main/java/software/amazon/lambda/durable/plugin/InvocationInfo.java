// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import java.util.Map;

/**
 * Invocation-level information available to plugin hooks.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution (not a replay invocation)
 * @param executionStartTime the start timestamp of the durable execution, taken from the initial EXECUTION operation in
 *     the first event delivered by the backend. Stable across all invocations of the same execution.
 * @param operations a snapshot of the checkpointed operations delivered at the start of this invocation, keyed by
 *     operation ID. Includes the initial EXECUTION operation. Empty-but-never-null.
 * @param updatedOperations the subset of {@code operations} that changed externally between the previous invocation and
 *     this one (a wait timer expired, a callback was received, a chained invoke completed), keyed by operation ID.
 *     Sourced from the {@code UpdatedOperationIds} field of the durable invocation input, so it is empty on the first
 *     invocation. Empty-but-never-null.
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record InvocationInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        Instant executionStartTime,
        Map<String, OperationChangeItemInfo> operations,
        Map<String, OperationChangeItemInfo> updatedOperations) {}
