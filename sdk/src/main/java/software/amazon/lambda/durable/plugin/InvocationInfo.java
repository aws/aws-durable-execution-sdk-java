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
 * <p>The nullable runtime header is the eighth record component. Legacy constructors remain available, but source
 * record patterns must include the new component; reflection and record value semantics observe the new field.
 *
 * @param requestId the Lambda request ID for this invocation
 * @param durableExecutionArn the durable execution ARN
 * @param isFirstInvocation true if this is the first invocation of the execution (not a replay invocation)
 * @param executionStartTime the start timestamp of the durable execution, taken from the initial EXECUTION operation in
 *     the first event delivered by the backend. Never null and stable across all invocations of the same execution.
 * @param executionInput the deserialized execution input passed to the user handler, or null when unavailable
 * @param operations checkpointed operations delivered at invocation start, keyed by operation ID; this component is
 *     experimental
 * @param updatedOperations operations changed externally since the previous invocation, keyed by operation ID; this
 *     component is experimental
 * @param xRayTraceId immutable invocation-local X-Ray header; null means unavailable and permits legacy carrier
 *     fallback, while an empty string means an available runtime supplied no header
 */
public record InvocationInfo(
        String requestId,
        String durableExecutionArn,
        boolean isFirstInvocation,
        Instant executionStartTime,
        @Experimental Object executionInput,
        @Experimental Map<String, OperationChangeItemInfo> operations,
        @Experimental Map<String, OperationChangeItemInfo> updatedOperations,
        String xRayTraceId) {

    public InvocationInfo {
        requireNonNull(executionStartTime, "executionStartTime");
        requireNonNull(operations, "operations");
        requireNonNull(updatedOperations, "updatedOperations");
    }

    /** Retains the original seven-argument constructor for callers without a runtime header snapshot. */
    public InvocationInfo(
            String requestId,
            String durableExecutionArn,
            boolean isFirstInvocation,
            Instant executionStartTime,
            Object executionInput,
            Map<String, OperationChangeItemInfo> operations,
            Map<String, OperationChangeItemInfo> updatedOperations) {
        this(
                requestId,
                durableExecutionArn,
                isFirstInvocation,
                executionStartTime,
                executionInput,
                operations,
                updatedOperations,
                null);
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

    /** Returns a representation that omits execution payloads, operation snapshots, and the runtime header. */
    @Override
    public String toString() {
        return "InvocationInfo[requestId=" + requestId + ", durableExecutionArn=" + durableExecutionArn
                + ", isFirstInvocation=" + isFirstInvocation + ", executionStartTime=" + executionStartTime + "]";
    }
}
