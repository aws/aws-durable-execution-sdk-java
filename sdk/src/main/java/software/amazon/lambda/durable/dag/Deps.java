// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.Optional;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Typed accessor for the results of a DAG task's upstream (inline) dependencies. Passed as the first parameter of every
 * DAG task function.
 *
 * <p>This is Java's answer to the JS {@code DepsMap}: instead of literal-string type keys, a result is retrieved by
 * passing the upstream task's {@link TaskHandle}, which carries the result type via generics. Only handles declared via
 * {@code TaskHandle.reads(...)} are retrievable; ordering-only dependencies (declared via {@code after(...)}) are not.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public interface Deps {

    /**
     * Returns the checkpointed result of an upstream inline dependency as an {@link Optional}.
     *
     * <p>The result is {@link Optional#empty()} whenever the upstream did not produce a success value — i.e. it FAILED
     * or was SKIPPED. This is possible under non-ALL_SUCCESS trigger rules ({@code ALL_DONE}, {@code ANY_FAILED},
     * {@code NONE_FAILED}, {@code ALL_FAILED}), where a task can run even though one of its inline dependencies did not
     * succeed. For the default {@code ALL_SUCCESS} trigger rule the value is always present, so callers may unwrap with
     * {@link Optional#orElseThrow()}.
     *
     * @param <T> the upstream task's result type
     * @param handle the upstream task's handle (must be an inline dependency declared via {@code reads(...)})
     * @return the upstream result, or {@link Optional#empty()} if the upstream did not SUCCEED
     * @throws IllegalStateException if {@code handle} was not declared as an inline dependency of this task
     */
    <T> Optional<T> get(TaskHandle<T> handle);
}
