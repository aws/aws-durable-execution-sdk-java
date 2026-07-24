// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * A DAG step body for the positional-arity sugar with three typed upstream dependencies: receives the resolved results
 * of dependencies {@code A}, {@code B} and {@code C} directly (instead of a {@link Deps} accessor) plus a
 * {@link StepContext}. Desugars to {@code step(...).reads(a, b, c)} + {@link Deps#get}.
 *
 * @param <A> the first upstream dependency's result type
 * @param <B> the second upstream dependency's result type
 * @param <C> the third upstream dependency's result type
 * @param <T> the step result type
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
@FunctionalInterface
public interface DagStep3Function<A, B, C, T> {
    T apply(A a, B b, C c, StepContext ctx);
}
