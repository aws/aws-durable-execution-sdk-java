// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Determines whether a DAG task runs based on the terminal statuses of its upstream dependencies.
 *
 * <p>A pure value type: the rule's truth function (including the empty-upstream/vacuous case for root tasks or tasks
 * with no dependencies) is evaluated internally by the scheduler. The default rule is {@link #ALL_SUCCESS}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public enum TriggerRule {
    /** Run only if every upstream SUCCEEDED. Empty upstream: run (vacuously true). */
    ALL_SUCCESS,
    /** Run only if every upstream FAILED. Empty upstream: skip. */
    ALL_FAILED,
    /** Run once every upstream is terminal, regardless of outcome. Empty upstream: run. */
    ALL_DONE,
    /** Run if at least one upstream SUCCEEDED. Empty upstream: skip. */
    ANY_SUCCESS,
    /** Run if at least one upstream FAILED. Empty upstream: skip. */
    ANY_FAILED,
    /** Run if no upstream FAILED (SUCCEEDED and SKIPPED are fine). Empty upstream: run. */
    NONE_FAILED
}
