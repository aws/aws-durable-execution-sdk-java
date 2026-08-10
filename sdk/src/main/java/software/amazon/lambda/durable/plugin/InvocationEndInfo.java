// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import java.util.Map;

/**
 * Information provided at the end of a Lambda invocation.
 *
 * <p>Carries the same invocation-identity surface as {@link InvocationInfo} so an invocation-end hook never has to
 * correlate back to the start hook to learn the execution start time or the operation state.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution
 * @param executionStartTime the start timestamp of the durable execution, taken from the initial EXECUTION operation in
 *     the first event delivered by the backend. Stable across all invocations of the same execution.
 * @param operations a snapshot of the checkpointed operations known when the invocation ended, keyed by operation ID.
 *     Unlike {@link InvocationInfo#operations()} this includes operations created during this invocation. Includes the
 *     initial EXECUTION operation. Empty-but-never-null.
 * @param invocationStatus the invocation outcome (SUCCEEDED, FAILED, or PENDING)
 * @param executionError non-null if the execution failed
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record InvocationEndInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        Instant executionStartTime,
        Map<String, OperationChangeItemInfo> operations,
        InvocationStatus invocationStatus,
        Throwable executionError) {}
