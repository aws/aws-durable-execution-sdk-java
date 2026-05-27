// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;

/**
 * Extended operation information for operation end events.
 *
 * @param id operation ID — hashed
 * @param rawId operation ID — unhashed
 * @param name human-readable operation name (may be null)
 * @param type operation type
 * @param subType operation sub-type (may be null)
 * @param parentId parent operation ID — hashed (null for root-level operations)
 * @param rawParentId parent operation ID — unhashed (null for root-level operations)
 * @param startTimestamp when the operation started
 * @param endTimestamp when the operation ended
 * @param error non-null if the operation failed
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record OperationEndInfo(
        String id,
        String rawId,
        String name,
        String type,
        String subType,
        String parentId,
        String rawParentId,
        Instant startTimestamp,
        Instant endTimestamp,
        Throwable error) {}
