// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Extended operation information for operation end events.
 *
 * @param id operation ID
 * @param name human-readable operation name (may be null)
 * @param type operation type
 * @param subType operation sub-type (may be null)
 * @param parentId parent operation ID (null for root-level operations)
 * @param startTimestamp when the operation started
 * @param endTimestamp when the operation ended
 * @param status the operation's terminal status (e.g., SUCCEEDED, FAILED, TIMED_OUT) — may be null for virtual ops
 * @param attempt the total number of attempts for retriable operations (STEP, WAIT_FOR_CONDITION) — null for others
 * @param isReplay true if this operation already existed in the execution state (completed in a prior invocation)
 * @param error non-null if the operation failed; this component is experimental
 * @param result the serialized result of the operation, or null if the operation failed or has no result; this
 *     component is experimental
 */
public record OperationEndInfo(
        String id,
        String name,
        String type,
        String subType,
        String parentId,
        Instant startTimestamp,
        Instant endTimestamp,
        String status,
        Integer attempt,
        boolean isReplay,
        @Experimental Throwable error,
        @Experimental String result) {

    /**
     * Creates operation-end information without a result.
     *
     * <p>Retained so callers written before {@code result} was added keep compiling and linking; {@code result}
     * resolves to null.
     *
     * @param id operation ID
     * @param name human-readable operation name (may be null)
     * @param type operation type
     * @param subType operation sub-type (may be null)
     * @param parentId parent operation ID (null for root-level operations)
     * @param startTimestamp when the operation started
     * @param endTimestamp when the operation ended
     * @param status the operation's terminal status
     * @param attempt the total number of attempts for retriable operations
     * @param isReplay true if this operation already existed in the execution state
     * @param error non-null if the operation failed
     */
    public OperationEndInfo(
            String id,
            String name,
            String type,
            String subType,
            String parentId,
            Instant startTimestamp,
            Instant endTimestamp,
            String status,
            Integer attempt,
            boolean isReplay,
            Throwable error) {
        this(id, name, type, subType, parentId, startTimestamp, endTimestamp, status, attempt, isReplay, error, null);
    }

    /**
     * Returns a representation that omits {@code result}.
     *
     * <p>The generated representation would render the operation result, so plugins that log this object whole would
     * start emitting customer payloads, potentially including secrets or personal data. Read the component explicitly
     * to record it.
     */
    @Override
    public String toString() {
        return "OperationEndInfo[id=" + id + ", name=" + name + ", type=" + type + ", subType=" + subType
                + ", parentId=" + parentId + ", startTimestamp=" + startTimestamp + ", endTimestamp=" + endTimestamp
                + ", status=" + status + ", attempt=" + attempt + ", isReplay=" + isReplay + ", error=" + error + "]";
    }
}
