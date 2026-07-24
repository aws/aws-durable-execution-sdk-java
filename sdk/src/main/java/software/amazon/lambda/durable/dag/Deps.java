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
 * {@code TaskHandle.reads(...)} are retrievable; ordering-only dependencies (declared via {@code dependsOn(...)}) are
 * not.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public interface Deps {

    /**
     * Returns the checkpointed result of an upstream inline dependency.
     *
     * @param <T> the upstream task's result type
     * @param handle the upstream task's handle (must be an inline dependency declared via {@code reads(...)})
     * @return the upstream result; may be {@code null} if the upstream did not SUCCEED (possible under non-ALL_SUCCESS
     *     trigger rules — see {@link #getOptional})
     * @throws IllegalStateException if {@code handle} was not declared as an inline dependency of this task
     */
    <T> T get(TaskHandle<T> handle);

    /**
     * Returns the checkpointed result of an upstream inline dependency as an {@link Optional}, empty when the upstream
     * did not produce a success value (FAILED/SKIPPED). Convenience for non-ALL_SUCCESS trigger rules.
     *
     * @param <T> the upstream task's result type
     * @param handle the upstream task's handle (must be an inline dependency declared via {@code reads(...)})
     * @return the upstream result, or {@link Optional#empty()} if absent
     * @throws IllegalStateException if {@code handle} was not declared as an inline dependency of this task
     */
    <T> Optional<T> getOptional(TaskHandle<T> handle);
}
