// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Aggregate result of a completed DAG. Provides typed and untyped accessors for individual task results and statuses,
 * grouped views, counts, the completion reason, and a fail-fast helper.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public interface DagResult {

    /** Typed result of a task by handle. Empty if the task was skipped, never started, or did not succeed. */
    <T> Optional<T> getResult(TaskHandle<T> handle);

    /** Untyped result of a task by name. */
    Optional<Object> getResult(String name);

    /** Terminal status of a task by handle. */
    Optional<TaskStatus> getStatus(TaskHandle<?> handle);

    /** Terminal status of a task by name. */
    Optional<TaskStatus> getStatus(String name);

    /** All succeeded task executions. */
    List<TaskExecution<?>> succeeded();

    /** All failed task executions. */
    List<TaskExecution<?>> failed();

    /** All skipped task executions. */
    List<TaskExecution<?>> skipped();

    /** All task executions keyed by name (unmodifiable). */
    Map<String, TaskExecution<?>> results();

    int successCount();

    int failureCount();

    int skippedCount();

    int totalCount();

    /** Why the DAG finished. */
    DagCompletionReason completionReason();

    /** Throws {@link DagExecutionException} if {@link #failureCount()} {@code > 0}. */
    void throwIfError();
}
