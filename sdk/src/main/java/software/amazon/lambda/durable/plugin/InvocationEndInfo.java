// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Information provided at the end of a Lambda invocation.
 *
 * <p>Carries the same invocation-identity surface as {@link InvocationInfo} so an invocation-end hook does not have to
 * correlate back to the start hook to learn the execution start time or operation state.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution
 * @param executionStartTime the stable start timestamp of the durable execution
 * @param operations a snapshot of operations known when the invocation ended, keyed by operation ID
 * @param invocationStatus the invocation outcome
 * @param executionError non-null if the execution failed; this component is experimental
 * @param executionInput the deserialized execution input; this component is experimental
 * @param executionResult the value returned by a successful handler; this component is experimental
 */
public record InvocationEndInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        Instant executionStartTime,
        Map<String, OperationChangeItemInfo> operations,
        InvocationStatus invocationStatus,
        @Experimental Throwable executionError,
        @Experimental Object executionInput,
        @Experimental Object executionResult) {

    /** Creates invocation-end information without execution payloads or operation state. */
    public InvocationEndInfo(
            String requestId,
            String durableExecutionArn,
            boolean isFirstInvocation,
            InvocationStatus invocationStatus,
            Throwable executionError) {
        this(
                requestId,
                durableExecutionArn,
                isFirstInvocation,
                null,
                Map.of(),
                invocationStatus,
                executionError,
                null,
                null);
    }

    /** Creates invocation-end information without execution start time or operation state. */
    public InvocationEndInfo(
            String requestId,
            String durableExecutionArn,
            boolean isFirstInvocation,
            InvocationStatus invocationStatus,
            Throwable executionError,
            Object executionInput,
            Object executionResult) {
        this(
                requestId,
                durableExecutionArn,
                isFirstInvocation,
                null,
                Map.of(),
                invocationStatus,
                executionError,
                executionInput,
                executionResult);
    }

    /** Returns a representation that omits execution payloads and operation snapshots. */
    @Override
    public String toString() {
        return "InvocationEndInfo[requestId=" + requestId + ", durableExecutionArn=" + durableExecutionArn
                + ", isFirstInvocation=" + isFirstInvocation + ", invocationStatus=" + invocationStatus
                + ", executionError=" + executionError + "]";
    }
}
