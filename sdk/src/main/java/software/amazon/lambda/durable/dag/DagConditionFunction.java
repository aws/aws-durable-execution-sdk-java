// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/**
 * A DAG waitForCondition check body: receives resolved upstream results ({@link Deps}), the current state, and a
 * {@link StepContext}, returning a {@link WaitForConditionResult}. Mirrors the native {@code BiFunction<S, StepContext,
 * WaitForConditionResult<S>>} shape plus {@link Deps}.
 *
 * @param <S> the polled state type
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
@FunctionalInterface
public interface DagConditionFunction<S> {
    WaitForConditionResult<S> apply(Deps deps, S state, StepContext ctx);
}
