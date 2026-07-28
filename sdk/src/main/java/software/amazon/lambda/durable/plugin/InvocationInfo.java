// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;

/**
 * Invocation-level information available to plugin hooks.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution (not a replay invocation)
 * @param executionStartTime the start timestamp of the durable execution, taken from the initial EXECUTION operation in
 *     the first event delivered by the backend. Stable across all invocations of the same execution.
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record InvocationInfo(
        String requestId, String durableExecutionArn, boolean isFirstInvocation, Instant executionStartTime) {}
