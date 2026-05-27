// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;

/**
 * Information provided when a user function starts executing.
 *
 * <p>This fires for both step attempts and child context functions, on the same thread as the user code. For steps,
 * {@code attempt} is non-null (1-based). For context operations, {@code attempt} is null.
 *
 * @param id operation ID — hashed
 * @param rawId operation ID — unhashed
 * @param name human-readable operation name (may be null)
 * @param type operation type (STEP, CONTEXT, etc.)
 * @param subType operation sub-type (Map, Parallel, WaitForCondition, etc.) — may be null
 * @param parentId parent operation ID — hashed (null for root-level operations)
 * @param rawParentId parent operation ID — unhashed (null for root-level operations)
 * @param startTimestamp when the user function started
 * @param isReplay true if the user function is being called during replay (context operations)
 * @param attempt 1-based attempt number for steps/waitForCondition, null for context operations
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record UserFunctionStartInfo(
        String id,
        String rawId,
        String name,
        String type,
        String subType,
        String parentId,
        String rawParentId,
        Instant startTimestamp,
        boolean isReplay,
        Integer attempt) {}
