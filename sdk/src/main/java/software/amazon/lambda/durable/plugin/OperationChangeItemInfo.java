// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Operation-level information for a single operation within an {@link OperationChangeInfo}.
 *
 * @param id operation ID
 * @param name human-readable operation name (may be null)
 * @param type operation type
 * @param subType operation sub-type (may be null)
 * @param parentId parent operation ID (null for root-level operations)
 * @param startTimestamp when the operation started
 * @param endTimestamp when the operation ended
 * @param error non-null if the operation failed; this component is experimental
 * @param status operation status
 */
public record OperationChangeItemInfo(
        String id,
        String name,
        String type,
        String subType,
        String parentId,
        Instant startTimestamp,
        Instant endTimestamp,
        @Experimental Throwable error,
        OperationStatus status) {}
