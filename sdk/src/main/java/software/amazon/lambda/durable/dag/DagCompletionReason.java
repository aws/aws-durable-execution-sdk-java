// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Why a DAG finished. A DAG-local superset of the base SDK's {@code ConcurrencyCompletionStatus} (which cannot express
 * the {@link #COMPLETED_WITH_FAILURES} distinction).
 *
 * <p>In v1 only threshold completion is supported (custom-predicate result-based completion is deferred to v2 in Java,
 * so no custom-completion members are defined here).
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public enum DagCompletionReason {
    /** Default drain: every reachable task succeeded or was skipped (no failures). */
    ALL_COMPLETED,
    /** Default drain: the reachable graph fully drained but at least one task FAILED. */
    COMPLETED_WITH_FAILURES,
    /** Early completion: a {@code minSuccessful} threshold was reached. */
    MIN_SUCCESSFUL_REACHED,
    /** Early completion: a tolerated-failure threshold was exceeded. */
    FAILURE_TOLERANCE_EXCEEDED
}
