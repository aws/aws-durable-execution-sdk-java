// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Operation-level information for an {@link OperationChangeInfo} and invocation operation snapshot.
 *
 * @param id operation ID
 * @param name human-readable operation name (may be null)
 * @param type operation type
 * @param subType operation sub-type (may be null)
 * @param parentId parent operation ID (null for root-level operations)
 * @param startTimestamp when the operation started
 * @param endTimestamp when the operation ended
 * @param status operation status
 * @param attempt attempt number for retriable operations, null for others
 * @param isReplay true if this operation was present in state delivered at invocation start
 * @param error non-null if the operation failed; this component is experimental
 * @param result checkpointed serialized result, or null if unavailable; this component is experimental
 */
public record OperationChangeItemInfo(
        String id,
        String name,
        String type,
        String subType,
        String parentId,
        Instant startTimestamp,
        Instant endTimestamp,
        OperationStatus status,
        Integer attempt,
        boolean isReplay,
        @Experimental Throwable error,
        @Experimental String result) {

    /** Returns a representation that omits the operation result payload. */
    @Override
    public String toString() {
        return "OperationChangeItemInfo[id=" + id + ", name=" + name + ", type=" + type + ", subType=" + subType
                + ", parentId=" + parentId + ", startTimestamp=" + startTimestamp + ", endTimestamp=" + endTimestamp
                + ", status=" + status + ", attempt=" + attempt + ", isReplay=" + isReplay + ", error=" + error + "]";
    }
}
