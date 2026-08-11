// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.List;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.dag.TriggerRule;

/**
 * Internal evaluator for {@link TriggerRule}. Keeps scheduler truth-table logic off the public value enum: given a rule
 * and the terminal statuses of a task's upstream dependencies, decides whether the task should run.
 *
 * <p>Each rule defines its behavior for the empty-upstream (vacuous) case used by root tasks or tasks whose dependency
 * set is empty.
 */
final class TriggerRuleEvaluator {

    private TriggerRuleEvaluator() {}

    /**
     * Evaluates a rule against the terminal statuses of a task's upstream dependencies.
     *
     * @param rule the trigger rule
     * @param statuses the terminal statuses of the upstream dependencies (may be empty)
     * @return {@code true} if the task should run, {@code false} if it should be skipped
     */
    static boolean eval(TriggerRule rule, List<TaskStatus> statuses) {
        return switch (rule) {
            // Empty upstream: vacuously true.
            case ALL_SUCCESS -> statuses.stream().allMatch(s -> s == TaskStatus.SUCCEEDED);
            // Empty upstream: skip.
            case ALL_FAILED -> !statuses.isEmpty() && statuses.stream().allMatch(s -> s == TaskStatus.FAILED);
            // Empty upstream: run.
            case ALL_DONE -> true;
            // Empty upstream: skip.
            case ANY_SUCCESS -> statuses.stream().anyMatch(s -> s == TaskStatus.SUCCEEDED);
            // Empty upstream: skip.
            case ANY_FAILED -> statuses.stream().anyMatch(s -> s == TaskStatus.FAILED);
            // Empty upstream: run.
            case NONE_FAILED -> statuses.stream().noneMatch(s -> s == TaskStatus.FAILED);
        };
    }
}
