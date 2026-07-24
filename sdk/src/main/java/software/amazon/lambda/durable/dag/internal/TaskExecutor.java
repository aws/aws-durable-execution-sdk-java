// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.dag.Deps;

/**
 * Internal closure that launches a task's underlying durable operation under an explicit, name-derived operation ID.
 * Created at registration time (capturing the user function + config) and invoked by the scheduler when the task is
 * ready.
 *
 * @param <T> the task result type
 */
@FunctionalInterface
public interface TaskExecutor<T> {

    /**
     * Launches the underlying operation via the matching {@code *AsyncWithId} entry point.
     *
     * @param ctx the DAG child context to launch the operation in
     * @param deps resolved upstream results for this task
     * @param operationId the precomputed, name-derived operation ID
     * @return a future representing the task result
     */
    DurableFuture<T> launch(DurableContextImpl ctx, Deps deps, String operationId);
}
