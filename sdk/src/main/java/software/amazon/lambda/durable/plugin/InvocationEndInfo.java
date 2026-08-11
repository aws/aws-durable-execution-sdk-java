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
 */
public record InvocationEndInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        InvocationStatus invocationStatus,
        @Experimental Throwable executionError) {}
