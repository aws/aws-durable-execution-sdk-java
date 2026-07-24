// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * A DAG runInChildContext task body: receives resolved upstream results ({@link Deps}) and a child
 * {@link DurableContext}.
 *
 * @param <T> the child context result type
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
@FunctionalInterface
public interface DagChildFunction<T> {
    T apply(Deps deps, DurableContext childCtx);
}
