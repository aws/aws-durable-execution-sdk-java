// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Invocation-level information available to plugin hooks.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution (not a replay invocation)
 * @param executionStartTime the start timestamp of the durable execution, taken from the initial EXECUTION operation in
 *     the first event delivered by the backend. Stable across all invocations of the same execution.
 * @param executionInput the deserialized execution input passed to the user handler, or null when no plugins are
 *     registered or the input could not be deserialized; this component is experimental
 */
public record InvocationInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        Instant executionStartTime,
        @Experimental Object executionInput) {

    /**
     * Creates invocation information without an execution input.
     *
     * <p>Retained so callers written before {@code executionInput} was added keep compiling; {@code executionInput}
     * resolves to null.
     *
     * @param requestId the Lambda request ID for this invocation
     * @param durableExecutionArn the durable execution ARN
     * @param isFirstInvocation true if this is the first invocation of the execution
     * @param executionStartTime the start timestamp of the durable execution
     */
    public InvocationInfo(
            String requestId, String durableExecutionArn, boolean isFirstInvocation, Instant executionStartTime) {
        this(requestId, durableExecutionArn, isFirstInvocation, executionStartTime, null);
    }

    /**
     * Returns a representation that omits {@code executionInput}.
     *
     * <p>The generated representation would render the execution input, so plugins that log this object whole would
     * start emitting customer payloads, potentially including secrets or personal data. Read the component explicitly
     * to record it.
     */
    @Override
    public String toString() {
        return "InvocationInfo[requestId=" + requestId + ", durableExecutionArn=" + durableExecutionArn
                + ", isFirstInvocation=" + isFirstInvocation + ", executionStartTime=" + executionStartTime + "]";
    }
}
