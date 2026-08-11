// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.dag.Deps;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;

/**
 * Internal closure that launches a task through a stable extension-operation reservation. Created at registration time
 * and invoked by the scheduler when the task is ready.
 *
 * @param <T> the task result type
 */
@FunctionalInterface
public interface TaskExecutor<T> {

    /**
     * Launches the underlying operation through the public extension SPI.
     *
     * @param ctx the DAG child context to launch the operation in
     * @param operation the task's stable one-shot reservation
     * @param deps resolved upstream results for this task
     * @return a future representing the task result
     */
    DurableFuture<T> launch(ExtensionContext ctx, ExtensionOperation operation, Deps deps);
}
