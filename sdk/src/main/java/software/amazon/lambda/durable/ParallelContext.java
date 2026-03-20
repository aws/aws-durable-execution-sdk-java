// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.operation.ParallelOperation;

/** User-facing context for managing parallel branch execution within a durable function. */
public class ParallelContext implements AutoCloseable, DurableFuture<ParallelResult> {

    private final ParallelOperation parallelOperation;
    private final DurableContext durableContext;
    private final AtomicBoolean joined = new AtomicBoolean(false);

    /**
     * Creates a new ParallelContext.
     *
     * @param parallelOperation the underlying parallel operation managing concurrency
     * @param durableContext the durable context for creating child operations
     */
    public ParallelContext(ParallelOperation parallelOperation, DurableContext durableContext) {
        this.parallelOperation = Objects.requireNonNull(parallelOperation, "parallelOperation cannot be null");
        this.durableContext = Objects.requireNonNull(durableContext, "durableContext cannot be null");
    }

    /**
     * Registers and immediately starts a branch (respects maxConcurrency).
     *
     * @param name the branch name
     * @param resultType the result type class
     * @param func the function to execute in the branch's child context
     * @param <T> the result type
     * @return a {@link DurableFuture} that will contain the branch result
     * @throws IllegalStateException if called after {@link #join()}
     */
    public <T> DurableFuture<T> branch(String name, Class<T> resultType, Function<DurableContext, T> func) {
        return branch(name, TypeToken.get(resultType), func);
    }

    /**
     * Registers and immediately starts a branch (respects maxConcurrency).
     *
     * @param name the branch name
     * @param resultType the result type token for generic types
     * @param func the function to execute in the branch's child context
     * @param <T> the result type
     * @return a {@link DurableFuture} that will contain the branch result
     * @throws IllegalStateException if called after {@link #join()}
     */
    public <T> DurableFuture<T> branch(String name, TypeToken<T> resultType, Function<DurableContext, T> func) {
        if (joined.get()) {
            throw new IllegalStateException("Cannot add branches after join() has been called");
        }
        return parallelOperation.addItem(
                name, func, resultType, durableContext.getDurableConfig().getSerDes());
    }

    /**
     * Waits for completion based on config rules (minSuccessful, toleratedFailureCount).
     *
     * <p>First validates that the number of registered branches is sufficient to satisfy the completion criteria. Then
     * blocks until completion criteria are met or failure threshold exceeded.
     *
     * @throws IllegalArgumentException if branch count cannot satisfy completion criteria
     * @throws software.amazon.lambda.durable.exception.ConcurrencyExecutionException if failure threshold exceeded
     */
    public void join() {
        if (!joined.compareAndSet(false, true)) {
            return;
        }
        parallelOperation.join();
    }

    /**
     * Blocks until the parallel operation completes and returns the {@link ParallelResult}.
     *
     * <p>Calling {@code get()} implicitly calls {@code join()} if it has not been called yet.
     *
     * @return the {@link ParallelResult} summarising branch outcomes
     */
    @Override
    public ParallelResult get() {
        joined.set(true);
        return parallelOperation.get();
    }

    /**
     * Calls {@link #join()} if not already called. Guarantees that all branches complete before the context is closed.
     */
    @Override
    public void close() {
        join();
    }
}
