// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.operation.DurableOperation;
import java.util.Arrays;
import java.util.List;

public class DurableFuture<T> {
    private final DurableOperation<T> operation;

    public DurableFuture(DurableOperation<T> operation) {
        this.operation = operation;
    }

    /**
     * Blocks until the operation completes and returns the result.
     *
     * <p>This delegates to operation.get() which handles: - Phaser blocking (arriveAndAwaitAdvance) - Thread
     * deregistration (allows suspension) - Thread reactivation (resumes execution) - Result retrieval
     *
     * @return the operation result
     */
    public T get() {
        return operation.get();
    }

    /**
     * Waits for all provided futures to complete and returns their results in order.
     *
     * <p>The futures are resolved sequentially, but since the underlying operations run concurrently, this effectively
     * waits for all operations to complete. During replay, completed operations return immediately.
     *
     * @param futures the futures to wait for
     * @param <T> the result type of the futures
     * @return a list of results in the same order as the input futures
     */
    @SafeVarargs
    public static <T> List<T> allOf(DurableFuture<T>... futures) {
        return Arrays.stream(futures).map(DurableFuture::get).toList();
    }

    /**
     * Waits for all provided futures to complete and returns their results in order.
     *
     * <p>The futures are resolved sequentially, but since the underlying operations run concurrently, this effectively
     * waits for all operations to complete. During replay, completed operations return immediately.
     *
     * @param futures the list of futures to wait for
     * @param <T> the result type of the futures
     * @return a list of results in the same order as the input futures
     */
    public static <T> List<T> allOf(List<DurableFuture<T>> futures) {
        return futures.stream().map(DurableFuture::get).toList();
    }
}
