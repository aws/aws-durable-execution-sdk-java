// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

/**
 * The terminal disposition a custom DAG completion predicate assigns to an early completion.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public enum DagCompletionOutcome {
    /** Marks the early completion as a success. */
    SUCCEEDED,
    /** Marks the early completion as a failure, even if no individual task failed. */
    FAILED
}
