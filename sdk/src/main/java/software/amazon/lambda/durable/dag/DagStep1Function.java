// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * A DAG step body for the positional-arity sugar with a single typed upstream dependency: receives the resolved result
 * of dependency {@code A} directly (instead of a {@link Deps} accessor) plus a {@link StepContext}. Desugars to
 * {@code step(...).reads(a)} + {@link Deps#get}.
 *
 * @param <A> the upstream dependency's result type
 * @param <T> the step result type
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
@FunctionalInterface
public interface DagStep1Function<A, T> {
    T apply(A a, StepContext ctx);
}
