// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    private final Supplier<Error> pluginFatal;
    private final AtomicReference<Error> terminalFailure = new AtomicReference<>();
    private final CompletableFuture<Void> abortSignal = new CompletableFuture<>();
    private final Set<CompletableFuture<Void>> activeRequests = ConcurrentHashMap.newKeySet();

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
        this(maxItemCount, maxBatchBytes, calculateItemSize, executeBatch, () -> null);
    }

    ApiRequestDelayedBatcher(
            int maxItemCount,
            int maxBatchBytes,
            Function<T, Integer> calculateItemSize,
            Consumer<List<T>> executeBatch,
            Supplier<Error> pluginFatal) {
        this.pluginFatal = pluginFatal;
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
            var fatal = getFatalFailure();
            if (fatal != null) return CompletableFuture.failedFuture(fatal);
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
        var observedFatal = getFatalFailure();
        if (observedFatal != null) abortPending(observedFatal);
        synchronized (delayedBatch) {
            // cancel the flush timer if it has not been triggered
            this.delayedBatchFlushTimer.cancel(false);
            // execute the current batch now
            flushDelayedBatch();
        }

        // wait for previous batches to be flushed
        // A terminal invocation failure must not wait for an already admitted backend call to return.
        CompletableFuture.anyOf(flushingQueueFuture, abortSignal).join();
        var fatal = getFatalFailure();
        if (fatal != null) throw new CompletionException(fatal);
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
        flushingQueueFuture = flushingQueueFuture.thenCompose(ignored -> flushObservedAsync());
    }

    private CompletableFuture<Void> flushObservedAsync() {
        var completion = new CompletableFuture<Void>();
        // Keep sequencing through the future, but let reported plugin fatals escape the actual worker.
        // CompletableFuture async stages would otherwise catch the fatal again after flushQueue rethrows it.
        Runnable flush = (Runnable & CompletableFuture.AsynchronousCompletionTask) () -> {
            try {
                flushQueue();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
                var fatal = terminalFailure.get();
                if (fatal != null) throw fatal;
            }
        };
        try {
            InternalExecutor.INSTANCE.execute(flush);
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    /** Call checkpoint API with items in the flushing queue */
    private void flushQueue() {
        // There could be more items to flush because
        // - remaining items that didn't fit in the previous checkpoint call
        // - new items being added when processing the previous items
        // This allows the items being added when making the checkpoint request to be immediately processed
        while (flushingQueue.peek() != null) {
            var flushingSize = 0L;
            var flushingItems = new ArrayList<Item<T>>();
            while (true) {
                var item = flushingQueue.peek();
                if (item == null) {
                    break;
                }

                var itemSizeInByte = calculateItemSize.apply(item.request);
                var canFit = flushingSize + itemSizeInByte <= maxBatchBytes;

                // Add the item if
                // - it can fit in one checkpoint call, or
                // - flushingItems is empty, so that we can try the big item even if it's bigger than the max batch size
                if (!flushingItems.isEmpty() && (!canFit || flushingItems.size() >= maxItemCount)) {
                    break;
                }

                // An external fatal publisher can drain the queue between the peek and this poll.
                Item<T> next;
                synchronized (delayedBatch) {
                    next = flushingQueue.poll();
                    if (next != null) activeRequests.add(next.result());
                }
                if (next == null) break;
                flushingItems.add(next);
                flushingSize += itemSizeInByte;
            }
            if (!flushingItems.isEmpty()) {
                try {
                    // requests might be null for polling requests
                    var requests = flushingItems.stream()
                            .map(Item::request)
                            .filter(Objects::nonNull)
                            .toList();
                    // Another worker may already have failed the invocation, or may do so during this call.
                    // A skipped checkpoint must never look like a successful START to an at-most-once step.
                    rethrowPluginFatalIfPresent();
                    executeBatch.accept(requests);
                    rethrowPluginFatalIfPresent();
                    for (Item<T> item : flushingItems) {
                        item.result().complete(null);
                    }
                } catch (Throwable ex) {
                    var fatal = getFatalFailure();
                    for (Item<T> item : flushingItems) {
                        item.result().completeExceptionally(fatal == null ? ex : fatal);
                    }
                    if (fatal != null) {
                        abortPending(fatal);
                        throw fatal;
                    }
                } finally {
                    flushingItems.forEach(item -> activeRequests.remove(item.result()));
                }
            }
        }
    }

    Error getFatalFailure() {
        var fatal = terminalFailure.get();
        return fatal == null ? pluginFatal.get() : fatal;
    }

    private void rethrowPluginFatalIfPresent() {
        var fatal = getFatalFailure();
        if (fatal != null) throw fatal;
    }

    /** Settles delayed, queued, and active request futures without interrupting an admitted backend call. */
    void abortPending(Error fatal) {
        terminalFailure.compareAndSet(null, fatal);
        var original = terminalFailure.get();
        var pending = new ArrayList<CompletableFuture<Void>>();
        synchronized (delayedBatch) {
            delayedBatchFlushTimer.cancel(false);
            delayedBatch.forEach(item -> pending.add(item.result()));
            delayedBatch.clear();
            Item<T> item;
            while ((item = flushingQueue.poll()) != null) pending.add(item.result());
            // Queue removal and active registration share this lock, so an item cannot escape the abort.
            pending.addAll(activeRequests);
        }
        pending.forEach(future -> future.completeExceptionally(original));
        abortSignal.completeExceptionally(original);
    }
}
