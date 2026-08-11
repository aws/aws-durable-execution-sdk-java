// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.Objects;
import java.util.function.Function;

/**
 * Custom-predicate DAG completion: a deterministic predicate evaluated over the DAG's live progress and task results
 * after every task settlement.
 *
 * @param shouldComplete the predicate; receives a {@link DagCompletionStatus} snapshot of everything settled so far
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record CustomDagCompletion(Function<DagCompletionStatus, DagCompletionDecision> shouldComplete)
        implements DagCompletionConfig {
    public CustomDagCompletion {
        Objects.requireNonNull(shouldComplete, "shouldComplete cannot be null");
    }
}
