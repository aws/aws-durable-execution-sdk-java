// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Invocation-level information available to plugin hooks.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution (not a replay invocation)
 * @param executionStartTime the start timestamp of the durable execution, taken from the initial EXECUTION operation in
 *     the first event delivered by the backend. Never null and stable across all invocations of the same execution.
 * @param executionInput the deserialized execution input passed to the user handler, or null when unavailable
 * @param operations checkpointed operations delivered at invocation start, keyed by operation ID
 * @param updatedOperations operations changed externally since the previous invocation, keyed by operation ID
 */
public record InvocationInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        Instant executionStartTime,
        @Experimental Object executionInput,
        Map<String, OperationChangeItemInfo> operations,
        Map<String, OperationChangeItemInfo> updatedOperations) {

    public InvocationInfo {
        requireNonNull(executionStartTime, "executionStartTime");
        requireNonNull(operations, "operations");
        requireNonNull(updatedOperations, "updatedOperations");
    }

    /** Creates invocation information without payload or operation snapshots. */
    public InvocationInfo(
            String requestId, String durableExecutionArn, boolean isFirstInvocation, Instant executionStartTime) {
        this(requestId, durableExecutionArn, isFirstInvocation, executionStartTime, null, Map.of(), Map.of());
    }

    /** Creates invocation information without operation snapshots. */
    public InvocationInfo(
            String requestId,
            String durableExecutionArn,
            boolean isFirstInvocation,
            Instant executionStartTime,
            Object executionInput) {
        this(requestId, durableExecutionArn, isFirstInvocation, executionStartTime, executionInput, Map.of(), Map.of());
    }

    /** Creates invocation information without an execution input. */
    public InvocationInfo(
            String requestId,
            String durableExecutionArn,
            boolean isFirstInvocation,
            Instant executionStartTime,
            Map<String, OperationChangeItemInfo> operations,
            Map<String, OperationChangeItemInfo> updatedOperations) {
        this(
                requestId,
                durableExecutionArn,
                isFirstInvocation,
                executionStartTime,
                null,
                operations,
                updatedOperations);
    }

    /** Returns a representation that omits execution payloads and operation snapshots. */
    @Override
    public String toString() {
        return "InvocationInfo[requestId=" + requestId + ", durableExecutionArn=" + durableExecutionArn
                + ", isFirstInvocation=" + isFirstInvocation + ", executionStartTime=" + executionStartTime + "]";
    }
}
