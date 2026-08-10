// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

/**
 * Operation-level information for a single operation within an {@link OperationChangeInfo}, and the snapshot record
 * used for the operation maps carried on {@link InvocationInfo} / {@link InvocationEndInfo}.
 *
 * <p>Carries the full operation field surface, mirroring {@link OperationEndInfo}, so a plugin observing an operation
 * through a change delta or an invocation-level map sees the same fields it would see through the per-operation hooks.
 *
 * @param id operation ID
 * @param name human-readable operation name (may be null)
 * @param type operation type
 * @param subType operation sub-type (may be null)
 * @param parentId parent operation ID (null for root-level operations)
 * @param startTimestamp when the operation started
 * @param endTimestamp when the operation ended
 * @param status operation status
 * @param attempt the attempt number for retriable operations (STEP, WAIT_FOR_CONDITION) — null for others
 * @param isReplay true if this operation was already present in the checkpointed state delivered at the start of the
 *     current invocation (i.e. it predates this invocation) rather than being created during it
 * @param error non-null if the operation failed
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
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
        Throwable error) {}
