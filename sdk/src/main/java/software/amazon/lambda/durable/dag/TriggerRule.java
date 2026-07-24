// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.List;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Determines whether a DAG task runs based on the terminal statuses of its upstream dependencies.
 *
 * <p>Each rule defines its own {@link #eval(List)} truth function, including the empty-upstream (vacuous) case for root
 * tasks or tasks whose dependency set is empty. The default rule is {@link #ALL_SUCCESS}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public enum TriggerRule {
    /** Run only if every upstream SUCCEEDED. Empty upstream: run (vacuously true). */
    ALL_SUCCESS {
        @Override
        public boolean eval(List<TaskStatus> statuses) {
            return statuses.stream().allMatch(s -> s == TaskStatus.SUCCEEDED);
        }
    },
    /** Run only if every upstream FAILED. Empty upstream: skip. */
    ALL_FAILED {
        @Override
        public boolean eval(List<TaskStatus> statuses) {
            return !statuses.isEmpty() && statuses.stream().allMatch(s -> s == TaskStatus.FAILED);
        }
    },
    /** Run once every upstream is terminal, regardless of outcome. Empty upstream: run. */
    ALL_DONE {
        @Override
        public boolean eval(List<TaskStatus> statuses) {
            return true;
        }
    },
    /** Run if at least one upstream SUCCEEDED. Empty upstream: skip. */
    ONE_SUCCESS {
        @Override
        public boolean eval(List<TaskStatus> statuses) {
            return statuses.stream().anyMatch(s -> s == TaskStatus.SUCCEEDED);
        }
    },
    /** Run if at least one upstream FAILED. Empty upstream: skip. */
    ONE_FAILED {
        @Override
        public boolean eval(List<TaskStatus> statuses) {
            return statuses.stream().anyMatch(s -> s == TaskStatus.FAILED);
        }
    },
    /** Run if no upstream FAILED (SUCCEEDED and SKIPPED are fine). Empty upstream: run. */
    NONE_FAILED {
        @Override
        public boolean eval(List<TaskStatus> statuses) {
            return statuses.stream().noneMatch(s -> s == TaskStatus.FAILED);
        }
    };

    /**
     * Evaluates this rule against the terminal statuses of a task's upstream dependencies.
     *
     * @param statuses the terminal statuses of the upstream dependencies (may be empty)
     * @return {@code true} if the task should run, {@code false} if it should be skipped
     */
    public abstract boolean eval(List<TaskStatus> statuses);
}
