// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Terminal (or in-progress) status of a single DAG task.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public enum TaskStatus {
    /** The task ran and completed successfully. */
    SUCCEEDED,
    /** The task ran and threw. A failure is a terminal task state, not an abort of the DAG. */
    FAILED,
    /** The task did not run because its trigger rule or {@code runIf} predicate was not satisfied. */
    SKIPPED,
    /** The task has been launched but has not yet reached a terminal state. */
    STARTED
}
