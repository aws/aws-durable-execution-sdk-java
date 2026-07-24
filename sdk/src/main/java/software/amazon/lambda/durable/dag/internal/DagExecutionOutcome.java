// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.Map;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.TaskExecution;

/**
 * Internal result of running the DAG scheduler: the terminal state of every task that reached a terminal state (keyed
 * by name, in registration order), why the DAG finished, and the number of registered tasks.
 *
 * @param results terminal task executions keyed by name
 * @param completionReason why the DAG finished
 * @param totalCount number of registered tasks (fixed; independent of early completion / never-started tasks, per spec
 *     §2.8)
 */
public record DagExecutionOutcome(
        Map<String, TaskExecution<?>> results, DagCompletionReason completionReason, int totalCount) {}
