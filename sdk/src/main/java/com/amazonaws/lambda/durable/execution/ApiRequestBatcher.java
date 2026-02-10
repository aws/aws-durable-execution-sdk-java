// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.util.ExceptionHelper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Batches API requests to optimize throughput by grouping individual calls into batch operations.
 * Batches are flushed when full, when size limits are reached, or after a timeout.
 *
 * @param <T> Request type
 */
public class ApiRequestBatcher<T> {

    /** Timeout before auto-flushing incomplete batch */
    private final Duration flushDelay;
    /** Maximum items allowed in a single batch */
    private final int maxItemCount;
    /** Maximum bytes allowed in a single batch */
    private final int maxBatchBytes;
    /** Calculates byte size of each request */
    private final Function<T, Integer> calculateItemSize;
    /** Executes the batch operation */
    private final Function<List<T>, CompletableFuture<Void>> executeBatch;

    private record Item<T>(T request, CompletableFuture<Void> result) {}

    /** Batch accumulator */
    private class Batch {
        /** Accumulated requests */
        private final List<Item<T>> items;
        /** Current batch size in bytes */
        private int totalBytes;

        Batch() {
            this.items = new ArrayList<>();
        }

        /** Adds request to batch and returns its result future */
        CompletableFuture<Void> add(T request) {
            totalBytes += calculateItemSize.apply(request);
            CompletableFuture<Void> result = new CompletableFuture<>();
            items.add(new Item<>(request, result));
            return result;
        }

        /** Returns true if request fits within byte limit */
        boolean canFit(T request) {
            return totalBytes + calculateItemSize.apply(request) <= maxBatchBytes;
        }

        /** Returns true if batch has reached item count limit */
        boolean isFull() {
            return items.size() >= maxItemCount;
        }

        /** Executes batch and completes all item futures */
        void execute() {
            List<T> requests = new ArrayList<>(items.size());
            for (Item<T> item : items) {
                requests.add(item.request());
            }

            executeBatch.apply(requests).whenComplete((v, ex) -> {
                if (ex == null) {
                    for (Item<T> item : items) {
                        item.result().complete(null);
                    }
                } else {
                    Throwable cause = ExceptionHelper.unwrapCompletableFuture(ex);
                    for (Item<T> item : items) {
                        item.result().completeExceptionally(cause);
                    }
                }
            });
        }
    }

    /** Synchronizes batch state access */
    private final Object lock = new Object();
    /** Current batch accepting requests */
    private Batch activeBatch;
    /** Timer to auto-flush incomplete batch */
    private CompletableFuture<Void> flushTimer;

    /**
     * Creates a new ApiRequestBatcher with the specified configuration.
     *
     * @param flushDelay Maximum time to wait before flushing a batch
     * @param maxItemCount Maximum number of items per batch
     * @param maxBatchBytes Maximum total size in bytes for all items in a batch
     * @param calculateItemSize Function to calculate the size in bytes of each item
     * @param executeBatch Function to execute the batch action
     */
    public ApiRequestBatcher(
            Duration flushDelay,
            int maxItemCount,
            int maxBatchBytes,
            Function<T, Integer> calculateItemSize,
            Function<List<T>, CompletableFuture<Void>> executeBatch) {
        this.flushDelay = flushDelay;
        this.maxItemCount = maxItemCount;
        this.maxBatchBytes = maxBatchBytes;
        this.calculateItemSize = calculateItemSize;
        this.executeBatch = executeBatch;
    }

    /**
     * Submits request for batched execution.
     *
     * @param request Request to batch
     * @return Future completed when batch executes
     */
    public CompletableFuture<Void> submit(T request) {
        CompletableFuture<Void> result;
        Batch batchToFlush = null;
        Batch fullBatch = null;

        synchronized (lock) {
            // Flush if request doesn't fit
            if (activeBatch != null && !activeBatch.canFit(request)) {
                batchToFlush = detachBatch();
            }

            // Create batch and start timer
            if (activeBatch == null) {
                activeBatch = new Batch();
                if (flushTimer != null) {
                    cancelTimer();
                }
            }

            result = activeBatch.add(request);

            // Flush if batch full
            if (activeBatch.isFull()) {
                fullBatch = detachBatch();
            }

            // Start flush timer for new batch
            if (activeBatch != null && flushTimer == null) {
                flushTimer = new CompletableFuture<>();
                flushTimer
                        .completeOnTimeout(null, flushDelay.toMillis(), TimeUnit.MILLISECONDS)
                        .thenRun(() -> {
                            Batch toFlush;
                            synchronized (lock) {
                                if (activeBatch != null) {
                                    toFlush = detachBatch();
                                } else {
                                    return;
                                }
                            }
                            toFlush.execute();
                        });
            }

            // Cancel timer if batch was flushed
            if (activeBatch == null && flushTimer != null) {
                cancelTimer();
            }
        }

        // Execute outside lock to avoid blocking
        if (batchToFlush != null) {
            batchToFlush.execute();
        }

        if (fullBatch != null) {
            fullBatch.execute();
        }

        return result;
    }

    /** Detaches and returns active batch */
    private Batch detachBatch() {
        if (activeBatch == null) {
            throw new IllegalStateException("activeBatch must not be null");
        }
        Batch batch = activeBatch;
        activeBatch = null;
        return batch;
    }

    /** Cancels and clears flush timer */
    private void cancelTimer() {
        if (flushTimer == null) {
            throw new IllegalStateException("flushTimer must not be null");
        }
        flushTimer.cancel(false);
        flushTimer = null;
    }
}
