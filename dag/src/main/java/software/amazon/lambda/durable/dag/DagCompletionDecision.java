// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

/**
 * The value a DAG custom completion predicate returns.
 *
 * @param complete whether the DAG should complete now
 * @param outcome the completion's disposition; only meaningful when {@code complete} is {@code true}, and defaults to
 *     {@link DagCompletionOutcome#SUCCEEDED} via {@link #complete()}
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record DagCompletionDecision(boolean complete, DagCompletionOutcome outcome) {

    /** Returns a decision meaning "keep scheduling ready tasks". */
    public static DagCompletionDecision continueDag() {
        return new DagCompletionDecision(false, null);
    }

    /** Returns a decision meaning "complete the DAG now" as a success. */
    public static DagCompletionDecision completeSuccessfully() {
        return new DagCompletionDecision(true, DagCompletionOutcome.SUCCEEDED);
    }

    /** Returns a decision meaning "complete the DAG now" with the given outcome. */
    public static DagCompletionDecision complete(DagCompletionOutcome outcome) {
        return new DagCompletionDecision(true, outcome == null ? DagCompletionOutcome.SUCCEEDED : outcome);
    }
}
