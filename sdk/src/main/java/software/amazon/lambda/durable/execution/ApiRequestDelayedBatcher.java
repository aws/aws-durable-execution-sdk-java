// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Batches API requests to optimize throughput by grouping individual calls into batch operations. Batches are flushed
 * when full, when size limits are reached, or after a timeout.
 *
 * <p>Uses a dedicated SDK thread pool for internal coordination, keeping checkpoint processing separate from
 * customer-configured executors used for user-defined operations.
 *
 * @see InternalExecutor
 * @param <T> Request type
 */
public class ApiRequestDelayedBatcher<T> {
    private static final Duration MAX_DELAY = Duration.ofMinutes(60);

    /** Maximum items allowed in a single batch */
    private final int maxItemCount;
    /** Maximum bytes allowed in a single batch */
    private final int maxBatchBytes;
    /** Calculates byte size of each request */
    private final Function<T, Integer> calculateItemSize;
    /** Executes the batch operation */
    private final Consumer<List<T>> executeBatch;

    /** Accumulated requests to be executed in future */
    private final List<Item<T>> delayedBatch;

    /** Time when the current batch must be flushed */
    private long delayedBatchFlushTime;

    /** Timer to auto-flush current batch */
    private CompletableFuture<Void> delayedBatchFlushTimer;

    /** Requests to be flushed */
    private final ConcurrentLinkedQueue<Item<T>> flushingQueue;

    /** Future of flushing items in queue */
    private CompletableFuture<Void> flushingQueueFuture;

    /**
     * Supplies the cause for each request rejected after abandonment. A supplier rather than one shared instance, so
     * every rejected caller gets an exception whose stack trace is its own rather than that of whichever thread first
     * detected the abandonment.
     */
    private volatile Supplier<? extends Throwable> abandonmentCause;

    private record Item<T>(T request, CompletableFuture<Void> result) {}

    /**
     * Creates a new ApiRequestDelayedBatcher with the specified configuration.
     *
     * @param maxItemCount Maximum number of items per batch
     * @param maxBatchBytes Maximum total size in bytes for all items in a batch
     * @param calculateItemSize Function to calculate the size in bytes of each item
     * @param executeBatch Function to execute the batch action
     */
    public ApiRequestDelayedBatcher(
            int maxItemCount,
            int maxBatchBytes,
            Function<T, Integer> calculateItemSize,
            Consumer<List<T>> executeBatch) {
        this.maxItemCount = maxItemCount;
        this.maxBatchBytes = maxBatchBytes;
        this.calculateItemSize = calculateItemSize;
        this.executeBatch = executeBatch;

        this.flushingQueueFuture = CompletableFuture.allOf();
        this.flushingQueue = new ConcurrentLinkedQueue<>();
        this.delayedBatch = new ArrayList<>();

        initializeDelayedBatch();
    }

    /**
     * Submits request for delayed execution.
     *
     * @param request Request to batch
     * @param flushDelay maximum delay of processing the request
     * @return Future completed when batch executes
     */
    CompletableFuture<Void> submit(T request, Duration flushDelay) {
        synchronized (delayedBatch) {
            var cause = abandonmentCause;
            if (cause != null) {
                return CompletableFuture.failedFuture(cause.get());
            }

            // add the request to the current batch
            CompletableFuture<Void> future = new CompletableFuture<>();
            delayedBatch.add(new Item<>(request, future));

            // The flush time of the current batch is determined by the earliest flush time in the batch.
            var delayInNano = flushDelay.toNanos();
            long newFlushTime = System.nanoTime() + delayInNano;
            if (newFlushTime < delayedBatchFlushTime) {
                // Schedule a new timer if the batch needs to be completed earlier than previously scheduled
                delayedBatchFlushTime = newFlushTime;
                delayedBatchFlushTimer.completeOnTimeout(null, delayInNano, TimeUnit.NANOSECONDS);
            }

            return future;
        }
    }

    /** Flushes pending batch and waits for completion */
    void shutdown() {
        synchronized (delayedBatch) {
            // cancel the flush timer if it has not been triggered
            this.delayedBatchFlushTimer.cancel(false);
            // execute the current batch now
            flushDelayedBatch();
        }

        // wait for previous batches to be flushed
        flushingQueueFuture.join();
    }

    /**
     * Rejects new and queued requests. A batch already executing is allowed to finish.
     *
     * @param cause supplies the cause used to complete each rejected request
     */
    void abandon(Supplier<? extends Throwable> cause) {
        var abandonedItems = new ArrayList<Item<T>>();
        synchronized (delayedBatch) {
            if (abandonmentCause != null) {
                return;
            }

            abandonmentCause = Objects.requireNonNull(cause);
            delayedBatchFlushTimer.cancel(false);
            abandonedItems.addAll(delayedBatch);
            delayedBatch.clear();

            Item<T> item;
            while ((item = flushingQueue.poll()) != null) {
                abandonedItems.add(item);
            }
        }

        completeExceptionally(abandonedItems, cause);
    }

    /** clear the current batch and creates a new batch */
    private void initializeDelayedBatch() {
        this.delayedBatch.clear();
        // MAX_DELAY is longer than a single Lambda invocation
        this.delayedBatchFlushTime = System.nanoTime() + MAX_DELAY.toNanos();

        // the timer future is created initially without a timeout until an item is added to the batch
        this.delayedBatchFlushTimer = new CompletableFuture<>();
        this.delayedBatchFlushTimer.thenRun(() -> {
            synchronized (delayedBatch) {
                flushDelayedBatch();
            }
        });
    }

    /** Add the delayed batch to the flushing queue */
    private void flushDelayedBatch() {
        // All the items in the delayed batch are flushed altogether, no matter if the scheduled time for the item has
        // arrived or not
        flushingQueue.addAll(delayedBatch);
        initializeDelayedBatch();

        if (flushingQueue.isEmpty()) {
            return;
        }

        // Schedule a new flushing future. If the items in this batch have been executed by the previous flushQueue
        // future,
        // the new future will just do nothing.
        flushingQueueFuture = flushingQueueFuture.thenRunAsync(this::flushQueue, InternalExecutor.INSTANCE);
    }
    /** Call checkpoint API with items in the flushing queue */
    private void flushQueue() {
        // There could be more items to flush because
        // - remaining items that didn't fit in the previous checkpoint call
        // - new items being added when processing the previous items
        // This allows the items being added when making the checkpoint request to be immediately processed
        while (flushingQueue.peek() != null) {
            List<Item<T>> flushingItems;
            synchronized (delayedBatch) {
                flushingItems = pollNextBatch();
            }
            if (flushingItems.isEmpty()) {
                continue;
            }

            var cause = abandonmentCause;
            if (cause != null) {
                completeExceptionally(flushingItems, cause);
                continue;
            }

            try {
                // requests might be null for polling requests
                var requests = flushingItems.stream()
                        .map(Item::request)
                        .filter(Objects::nonNull)
                        .toList();
                executeBatch.accept(requests);
                for (Item<T> item : flushingItems) {
                    item.result().complete(null);
                }
            } catch (Throwable ex) {
                completeExceptionally(flushingItems, () -> ex);
            }
        }
    }

    private List<Item<T>> pollNextBatch() {
        var flushingSize = 0L;
        var flushingItems = new ArrayList<Item<T>>();
        while (true) {
            var item = flushingQueue.peek();
            if (item == null) {
                return flushingItems;
            }

            var itemSizeInByte = calculateItemSize.apply(item.request);
            var canFit = flushingSize + itemSizeInByte <= maxBatchBytes;
            if (!flushingItems.isEmpty() && (!canFit || flushingItems.size() >= maxItemCount)) {
                return flushingItems;
            }

            flushingItems.add(flushingQueue.remove());
            flushingSize += itemSizeInByte;
        }
    }

    private void completeExceptionally(List<Item<T>> items, Supplier<? extends Throwable> cause) {
        items.forEach(item -> item.result().completeExceptionally(cause.get()));
    }
}
