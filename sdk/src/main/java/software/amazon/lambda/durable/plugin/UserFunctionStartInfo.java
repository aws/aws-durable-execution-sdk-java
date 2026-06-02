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
 * @param id operation ID
 * @param name human-readable operation name (may be null)
 * @param type operation type (STEP, CONTEXT, etc.)
 * @param subType operation sub-type (Map, Parallel, WaitForCondition, etc.) — may be null
 * @param parentId parent operation ID (null for root-level operations)
 * @param startTimestamp when the user function started
 * @param isReplayingChildren true if child operations within this context are being replayed from checkpoints
 * @param attempt 1-based attempt number for steps/waitForCondition, null for context operations
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record UserFunctionStartInfo(
        String id,
        String name,
        String type,
        String subType,
        String parentId,
        Instant startTimestamp,
        boolean isReplayingChildren,
        Integer attempt) {}
