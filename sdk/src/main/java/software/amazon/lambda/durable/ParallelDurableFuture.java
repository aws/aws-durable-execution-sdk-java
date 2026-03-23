// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.function.Function;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.model.ParallelResult;

/** User-facing context for managing parallel branch execution within a durable function. */
public interface ParallelDurableFuture extends AutoCloseable, DurableFuture<ParallelResult> {

    /**
     * Registers and immediately starts a branch (respects maxConcurrency).
     *
     * @param name the branch name
     * @param resultType the result type token for generic types
     * @param func the function to execute in the branch's child context
     * @param <T> the result type
     * @return a {@link DurableFuture} that will contain the branch result
     * @throws IllegalStateException if called after {@link #close()}
     */
    default <T> DurableFuture<T> branch(String name, Class<T> resultType, Function<DurableContext, T> func) {
        return branch(
                name,
                TypeToken.get(resultType),
                func,
                ParallelBranchConfig.builder().build());
    }

    /**
     * Registers and immediately starts a branch (respects maxConcurrency).
     *
     * @param name the branch name
     * @param resultType the result type token for generic types
     * @param func the function to execute in the branch's child context
     * @param <T> the result type
     * @return a {@link DurableFuture} that will contain the branch result
     * @throws IllegalStateException if called after {@link #close()}
     */
    default <T> DurableFuture<T> branch(String name, TypeToken<T> resultType, Function<DurableContext, T> func) {
        return branch(name, resultType, func, ParallelBranchConfig.builder().build());
    }

    /**
     * Registers and immediately starts a branch (respects maxConcurrency).
     *
     * @param name the branch name
     * @param resultType the result type token for generic types
     * @param func the function to execute in the branch's child context
     * @param <T> the result type
     * @return a {@link DurableFuture} that will contain the branch result
     * @throws IllegalStateException if called after {@link #close()}
     */
    default <T> DurableFuture<T> branch(
            String name, Class<T> resultType, Function<DurableContext, T> func, ParallelBranchConfig config) {
        return branch(name, TypeToken.get(resultType), func, config);
    }

    /**
     * Registers and immediately starts a branch (respects maxConcurrency).
     *
     * @param name the branch name
     * @param resultType the result type token for generic types
     * @param func the function to execute in the branch's child context
     * @param <T> the result type
     * @return a {@link DurableFuture} that will contain the branch result
     * @throws IllegalStateException if called after {@link #close()}
     */
    <T> DurableFuture<T> branch(
            String name, TypeToken<T> resultType, Function<DurableContext, T> func, ParallelBranchConfig config);

    /** Calls {@link #get()} if not already called. Guarantees that the context is closed. */
    void close();
}
