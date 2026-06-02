// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;

/**
 * Operation-level information available to plugin hooks.
 *
 * <p>Field names mirror the {@code Operation} type from the AWS SDK for consistency.
 *
 * @param id operation ID (unique within the execution)
 * @param name human-readable operation name (may be null)
 * @param type operation type (STEP, WAIT, CONTEXT, CHAINED_INVOKE, CALLBACK)
 * @param subType operation sub-type (Map, Parallel, RunInChildContext, WaitForCondition, etc.) — may be null
 * @param parentId parent operation ID (null for root-level operations)
 * @param startTimestamp when the operation started — on first execution this is a local {@code Instant.now()} which may
 *     slightly differ from the timestamp recorded by the backend; on replay it comes from the backend checkpoint
 * @param endTimestamp when the operation ended (null if still running)
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record OperationInfo(
        String id,
        String name,
        String type,
        String subType,
        String parentId,
        Instant startTimestamp,
        Instant endTimestamp) {}
