// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Batches API requests to optimize throughput by grouping individual calls into batch operations. Batches are flushed
 * when full, when size limits are reached, or after a timeout.
 *
 * @param <T> Request type
 */
public class ApiRequestBatcher<T> {
    private static final Duration MAX_DELAY = Duration.ofMinutes(60);

    /** Maximum items allowed in a single batch */
    private final int maxItemCount;
    /** Maximum bytes allowed in a single batch */
    private final int maxBatchBytes;
    /** Calculates byte size of each request */
    private final Function<T, Integer> calculateItemSize;
    /** Executes the batch operation */
    private final Consumer<List<T>> executeBatch;

    /** Accumulated requests */
    private final List<Item<T>> items;

    /** Current batch size in bytes */
    private volatile int totalBytes;

    /** Time when the current batch must be flushed */
    private volatile long expireTime;

    /** Timer to auto-flush incomplete batch */
    private CompletableFuture<Void> flushTimer;

    /** Future of flushing previous batch */
    private CompletableFuture<Void> previousBatchFuture;

    private record Item<T>(T request, CompletableFuture<Void> result) {}

    /**
     * Creates a new ApiRequestBatcher with the specified configuration.
     *
     * @param maxItemCount Maximum number of items per batch
     * @param maxBatchBytes Maximum total size in bytes for all items in a batch
     * @param calculateItemSize Function to calculate the size in bytes of each item
     * @param executeBatch Function to execute the batch action
     */
    public ApiRequestBatcher(
            int maxItemCount,
            int maxBatchBytes,
            Function<T, Integer> calculateItemSize,
            Consumer<List<T>> executeBatch) {
        this.maxItemCount = maxItemCount;
        this.maxBatchBytes = maxBatchBytes;
        this.calculateItemSize = calculateItemSize;
        this.executeBatch = executeBatch;
        this.previousBatchFuture = CompletableFuture.allOf();
        this.items = new ArrayList<>();

        initializeBatch();
    }

    private void initializeBatch() {
        this.items.clear();
        this.totalBytes = 0;
        this.expireTime = System.nanoTime() + MAX_DELAY.toNanos();
        this.flushTimer = new CompletableFuture<>();
        this.flushTimer.thenRun(() -> {
            synchronized (items) {
                execute();
            }
        });
    }

    /**
     * Submits request for batched execution.
     *
     * @param request Request to batch
     * @return Future completed when batch executes
     */
    public CompletableFuture<Void> submit(T request, Duration flushDelay) {
        // Flush the current batch if request doesn't fit
        synchronized (items) {
            if (isFull() || !canFit(request)) {
                flushNow();
            }

            var future = add(request, flushDelay);

            if (isFull()) {
                // Flush early if batch is full
                flushNow();
            }
            return future;
        }
    }

    /** Adds request to batch and returns its result future */
    CompletableFuture<Void> add(T request, Duration delay) {
        synchronized (items) {
            totalBytes += calculateItemSize.apply(request);
            CompletableFuture<Void> result = new CompletableFuture<>();
            items.add(new Item<>(request, result));
            long newExpireTime = System.nanoTime() + delay.toNanos();
            if (expireTime > newExpireTime) {
                // the batch needs to be completed earlier than previously scheduled
                expireTime = newExpireTime;
                flushAfterDelay(delay.toNanos());
            }
            return result;
        }
    }

    /** Returns true if request fits within byte limit */
    private boolean canFit(T request) {
        return totalBytes + calculateItemSize.apply(request) <= maxBatchBytes;
    }

    /** Returns true if batch has reached item count limit */
    private boolean isFull() {
        return items.size() >= maxItemCount;
    }

    private void flushAfterDelay(long delayInNanos) {
        flushTimer.completeOnTimeout(null, delayInNanos, TimeUnit.NANOSECONDS);
    }

    private void flushNow() {
        this.flushTimer.cancel(false);
        // wait for new batch to be ready
        execute();
    }

    /** Flushes pending batch and waits for completion */
    public void shutdown() {
        synchronized (items) {
            flushNow();
        }

        // wait for previous batches to be flushed
        previousBatchFuture.join();
    }

    /** Executes batch and completes all item futures */
    private void execute() {
        var copyItems = new ArrayList<>(items);
        initializeBatch();
        if (copyItems.isEmpty()) {
            return;
        }

        // append the current batch to the previous one
        previousBatchFuture = previousBatchFuture.thenRunAsync(
                () -> {
                    try {
                        var requests = copyItems.stream().map(Item::request).toList();
                        executeBatch.accept(requests);
                        for (Item<T> item : copyItems) {
                            item.result().complete(null);
                        }
                    } catch (Throwable ex) {
                        for (Item<T> item : copyItems) {
                            item.result().completeExceptionally(ex);
                        }
                    }
                },
                InternalExecutor.INSTANCE);
    }
}
