// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;

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
 * @param error non-null if the operation failed
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
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
        Throwable error) {}
