// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Information provided at the end of a Lambda invocation.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution
 * @param invocationStatus the invocation outcome (SUCCEEDED, FAILED, or PENDING)
 * @param executionError non-null if the execution failed; this component is experimental
 * @param executionInput the deserialized execution input passed to the user handler, or null when no plugins are
 *     registered or the input could not be deserialized; this component is experimental
 * @param executionResult the value the user handler returned, or null unless the invocation completed the execution
 *     successfully; this component is experimental
 */
public record InvocationEndInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        InvocationStatus invocationStatus,
        @Experimental Throwable executionError,
        @Experimental Object executionInput,
        @Experimental Object executionResult) {

    /**
     * Creates invocation-end information without the execution input or result.
     *
     * <p>Retained so callers written before {@code executionInput} and {@code executionResult} were added keep
     * compiling; both resolve to null.
     *
     * @param requestId the Lambda request ID for this invocation
     * @param durableExecutionArn the durable execution ARN
     * @param isFirstInvocation true if this is the first invocation of the execution
     * @param invocationStatus the invocation outcome
     * @param executionError non-null if the execution failed
     */
    public InvocationEndInfo(
            String requestId,
            String durableExecutionArn,
            boolean isFirstInvocation,
            InvocationStatus invocationStatus,
            Throwable executionError) {
        this(requestId, durableExecutionArn, isFirstInvocation, invocationStatus, executionError, null, null);
    }
}
