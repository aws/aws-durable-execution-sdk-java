// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Information provided when a user function finishes executing.
 *
 * <p>This fires for both step attempts and child context functions, on the same thread as the user code.
 *
 * @param id operation ID
 * @param name human-readable operation name (may be null)
 * @param type operation type (STEP, CONTEXT, etc.)
 * @param subType operation sub-type (Map, Parallel, WaitForCondition, etc.) — may be null
 * @param parentId parent operation ID (null for root-level operations)
 * @param startTimestamp when the user function started
 * @param endTimestamp when the user function ended
 * @param isReplay true if this operation was present in the checkpointed state delivered at invocation start
 * @param attempt 1-based attempt number for steps/waitForCondition, null for context operations
 * @param outcome the user function outcome
 * @param error non-null if the user function failed or exited incompletely; this component is experimental
 */
public record UserFunctionEndInfo(
        String id,
        String name,
        String type,
        String subType,
        String parentId,
        Instant startTimestamp,
        Instant endTimestamp,
        boolean isReplay,
        Integer attempt,
        UserFunctionOutcome outcome,
        @Experimental Throwable error) {}
