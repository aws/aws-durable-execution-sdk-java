// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.config.CompletionConfig;

/**
 * Controls when a DAG completes. In v1 only threshold-based completion is available, exposed via the six factory
 * methods below (mirroring the base SDK's {@code CompletionConfig} factories). Custom-predicate completion is deferred
 * to v2, so this sealed interface permits only {@link ThresholdDagCompletion}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public sealed interface DagCompletionConfig permits ThresholdDagCompletion {

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
}
