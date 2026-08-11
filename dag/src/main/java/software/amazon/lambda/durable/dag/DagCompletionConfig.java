// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.function.Function;
import software.amazon.lambda.durable.config.CompletionConfig;

/**
 * Controls when a DAG completes: threshold-based, via the six factory methods below (mirroring the base SDK's
 * {@code CompletionConfig} factories), or a custom, results-aware predicate via {@link #custom(Function)}. This sealed
 * interface permits {@link ThresholdDagCompletion} and {@link CustomDagCompletion}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public sealed interface DagCompletionConfig permits ThresholdDagCompletion, CustomDagCompletion {

    /** Every task must complete; failures tolerated (captured per-task). */
    static DagCompletionConfig allCompleted() {
        return new ThresholdDagCompletion(CompletionConfig.allCompleted());
    }

    /** Every task must succeed; zero failures tolerated. */
    static DagCompletionConfig allSuccessful() {
        return new ThresholdDagCompletion(CompletionConfig.allSuccessful());
    }

    /** Complete as soon as the first task succeeds. */
    static DagCompletionConfig firstSuccessful() {
        return new ThresholdDagCompletion(CompletionConfig.firstSuccessful());
    }

    /** Complete when {@code n} tasks have succeeded. */
    static DagCompletionConfig minSuccessful(int n) {
        return new ThresholdDagCompletion(CompletionConfig.minSuccessful(n));
    }

    /** Complete when more than {@code n} failures have occurred. */
    static DagCompletionConfig toleratedFailureCount(int n) {
        return new ThresholdDagCompletion(CompletionConfig.toleratedFailureCount(n));
    }

    /** Complete when the failure percentage exceeds {@code p} (0.0 to 1.0). */
    static DagCompletionConfig toleratedFailurePercentage(double p) {
        return new ThresholdDagCompletion(CompletionConfig.toleratedFailurePercentage(p));
    }

    /**
     * Complete based on a custom, results-aware predicate evaluated after every task settlement.
     *
     * <p>Unlike the threshold factories above, this predicate can inspect individual tasks' results (via
     * {@link DagCompletionStatus#items()} / {@link DagCompletionStatus#results()}), not just aggregate counts — for
     * example, stopping the moment any task's result matches a business condition.
     *
     * @param shouldComplete receives a live {@link DagCompletionStatus} snapshot; return
     *     {@link DagCompletionDecision#continueDag()} to keep scheduling or
     *     {@link DagCompletionDecision#complete(DagCompletionOutcome)} to stop the DAG now
     */
    static DagCompletionConfig custom(Function<DagCompletionStatus, DagCompletionDecision> shouldComplete) {
        return new CustomDagCompletion(shouldComplete);
    }
}
