// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * A DAG step task body: receives resolved upstream results ({@link Deps}, empty for roots) and a {@link StepContext}.
 *
 * @param <T> the step result type
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
@FunctionalInterface
public interface DagStepFunction<T> {
    T apply(Deps deps, StepContext ctx);
}
