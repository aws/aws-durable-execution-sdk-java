// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.time.Instant;
import java.util.Optional;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * The recorded outcome of a single DAG task.
 *
 * @param <T> the task result type
 * @param name the task name
 * @param status the terminal status
 * @param skipReason present only when {@code status == SKIPPED}
 * @param result present only when {@code status == SUCCEEDED}
 * @param error present only when {@code status == FAILED}
 * @param startedAt when the task started, if it ran
 * @param completedAt when the task completed, if it ran
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record TaskExecution<T>(
        String name,
        TaskStatus status,
        Optional<SkipReason> skipReason,
        Optional<T> result,
        Optional<DagTaskError> error,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt) {}
