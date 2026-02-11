// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    private record Item<T>(T request, CompletableFuture<Void> result) {}

    /** Batch accumulator */
    private class Batch {
        /** Accumulated requests */
        private final List<Item<T>> items;
        /** Current batch size in bytes */
        private int totalBytes;

        long expireTime;
        /** Timer to auto-flush incomplete batch */
        private final CompletableFuture<Void> flushTimer;

        Batch() {
            this.items = new ArrayList<>();
            this.totalBytes = 0;
            this.expireTime = System.nanoTime() + MAX_DELAY.toNanos();
            this.flushTimer = new CompletableFuture<>();
            this.flushTimer.thenRunAsync(this::execute, InternalExecutor.INSTANCE);
        }

        /** Adds request to batch and returns its result future */
        CompletableFuture<Void> add(T request, Duration delay) {
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

        /** Returns true if request fits within byte limit */
        boolean canFit(T request) {
            return totalBytes + calculateItemSize.apply(request) <= maxBatchBytes;
        }

        /** Returns true if batch has reached item count limit */
        boolean isFull() {
            return items.size() >= maxItemCount;
        }

        void flushAfterDelay(long delayInNanos) {
            flushTimer.completeOnTimeout(null, delayInNanos, TimeUnit.NANOSECONDS);
        }

        void flushNow() {
            flushAfterDelay(0);
        }

        void cancel() {
            var ex = new IllegalDurableOperationException("Batch cancelled");
            for (Item<T> item : items) {
                item.result().completeExceptionally(ex);
            }
        }

        /** Executes batch and completes all item futures */
        private void execute() {
            // detach this from active batch if it's still active
            detachActiveBatchAndCreateNew(this);

            List<T> requests = new ArrayList<>(items.size());
            for (Item<T> item : items) {
                requests.add(item.request());
            }

            try {
                executeBatch.accept(requests);
                for (Item<T> item : items) {
                    item.result().complete(null);
                }
            } catch (Throwable ex) {
                for (Item<T> item : items) {
                    item.result().completeExceptionally(ex);
                }
            }
        }
    }

    /** Current batch accepting requests */
    private final AtomicReference<Batch> activeBatchAtom;

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
        this.activeBatchAtom = new AtomicReference<>(new Batch());
    }

    /**
     * Submits request for batched execution.
     *
     * @param request Request to batch
     * @return Future completed when batch executes
     */
    public CompletableFuture<Void> submit(T request, Duration flushDelay) {
        // Flush the current batch if request doesn't fit
        while (true) {
            Batch activeBatch = activeBatchAtom.get();

            if (activeBatch.isFull() || !activeBatch.canFit(request)) {
                if (!flushActiveBatchAndCreateNew(activeBatch)) {
                    // failed to flush due to a race condition.
                    continue;
                }
            }

            var result = activeBatch.add(request, flushDelay);

            // Flush early if batch is full
            if (activeBatch.isFull()) {
                flushActiveBatchAndCreateNew(activeBatch);
            }
            return result;
        }
    }

    private Batch detachActiveBatchAndCreateNew(Batch oldBatch) {
        if (activeBatchAtom.compareAndSet(oldBatch, new Batch())) {
            return oldBatch;
        }

        return null;
    }

    /** flushes active batch and crate a new batch. Return true if successful */
    private boolean flushActiveBatchAndCreateNew(Batch oldBatch) {
        Batch activeBatch = detachActiveBatchAndCreateNew(oldBatch);
        if (activeBatch != null) {
            activeBatch.flushNow();
        }
        return activeBatch != null;
    }

    public void shutdown() {
        Batch activeBatch = activeBatchAtom.get();
        while (!activeBatchAtom.compareAndSet(activeBatch, new Batch())) {
            // try again
            activeBatch = activeBatchAtom.get();
        }
        activeBatchAtom.get().cancel();
    }
}
