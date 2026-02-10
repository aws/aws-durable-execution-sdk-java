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
import java.util.stream.Collectors;

/**
 * This class simplifies automatic batching of api requests. The individual request items will be grouped if the service
 * has a cheaper batch API, and we want to trade some latency by waiting for more calls to arrive. The batch call will
 * be made when either a full batch is built, too much time has passed, or size limits are reached. This class builds a
 * single batch at a time with thread-safe synchronization: - There is no batch yet. - First call arrives. Create a
 * batch with one item in it, start a timer. No call to service is made yet. - More calls arrive. They get added to the
 * same batch if size limits allow. - Either the batch is full, the timer has elapsed, or size limits are reached. Send
 * the batch request. Now a new batch can now be built. - If entire batch call fails, each call will fail. - If batch
 * call succeeded, outcome is analyzed one by one to complete results of each call. When you extend this class, you are
 * expected to implement the actual batch operation and to expose a public method to perform a single action. The
 * batcher includes comprehensive metrics tracking for performance monitoring.
 *
 * @param <T> Input of every call
 */
public class ApiRequestBatcher<T> {

    /** Maximum time to wait before flushing a batch */
    private final Duration maxDelay;
    /** Maximum number of items per batch */
    private final int maxBatchSize;
    /** Maximum total size in bytes for all items in a batch */
    private final int maxBatchBinarySizeInBytes;
    /** Function to calculate the size in bytes of each item */
    private final Function<T, Integer> itemSizeInBytesProvider;

    private final Function<List<T>, CompletableFuture<Void>> doBatchAction;

    private record BatchItem<T>(T input, CompletableFuture<Void> outputFuture) {}

    /** Represents a collection of items to be processed together as a batch. */
    private class Batch {
        /** List of items in this batch */
        private final List<BatchItem<T>> batchItems;
        /** Total size in bytes of all items in this batch */
        private int batchSizeInBytes;

        Batch() {
            this.batchItems = new ArrayList<>();
        }

        /**
         * Adds an item to this batch and returns a future for the result.
         *
         * @param input The item to add to the batch
         * @return A CompletableFuture that will be completed with the result
         */
        CompletableFuture<Void> addItem(T input) {
            final int itemSize = itemSizeInBytesProvider.apply(input);
            batchSizeInBytes += itemSize;

            CompletableFuture<Void> resultFuture = new CompletableFuture<>();
            batchItems.add(new BatchItem<>(input, resultFuture));
            return resultFuture;
        }

        /** Checks if this batch can accept the given item without exceeding size limits. */
        boolean canAcceptItem(T input) {
            return batchSizeInBytes + itemSizeInBytesProvider.apply(input) <= maxBatchBinarySizeInBytes;
        }

        /** Checks if this batch can accept more items without exceeding count limits. */
        boolean canAcceptMore() {
            return batchItems.size() < maxBatchSize;
        }

        /** Processes this batch by executing the batch action and handling results. */
        void processBatch() {
            List<T> inputs = extractInputs();

            CompletableFuture<Void> batchFuture = doBatchAction.apply(inputs);

            batchFuture.thenAccept(this::completeItems).exceptionally(this::failAllItems);
        }

        private List<T> extractInputs() {
            return batchItems.stream().map(BatchItem::input).collect(Collectors.toList());
        }

        /** Completes individual item futures with their corresponding results */
        private void completeItems(Void v) {
            for (BatchItem<T> batchItem : batchItems) {
                batchItem.outputFuture().complete(null);
            }
        }

        /** Fails all item futures with the given exception */
        private Void failAllItems(Throwable wrappedCause) {
            Throwable cause = ExceptionHelper.unwrapCompletableFuture(wrappedCause);
            for (BatchItem<T> batchItem : batchItems) {
                batchItem.outputFuture().completeExceptionally(cause);
            }
            return null;
        }
    }

    /** Lock for synchronizing access to current batch state */
    private final Object currentBatchLock = new Object();
    /** The current batch being filled with items */
    private Batch currentBatch;
    /** Future that completes when the current batch should be flushed due to timeout */
    private CompletableFuture<Void> batchFlushFuture;

    /**
     * Creates a new ApiRequestBatcher with the specified configuration.
     *
     * @param maxDelay Maximum time to wait before flushing a batch
     * @param maxBatchSize Maximum number of items per batch
     * @param maxBatchBinarySizeInBytes Maximum total size in bytes for all items in a batch
     * @param itemSizeInBytesProvider Function to calculate the size in bytes of each item
     */
    public ApiRequestBatcher(
            Duration maxDelay,
            int maxBatchSize,
            int maxBatchBinarySizeInBytes,
            Function<T, Integer> itemSizeInBytesProvider,
            Function<List<T>, CompletableFuture<Void>> doBatchAction) {
        this.maxDelay = maxDelay;
        this.maxBatchSize = maxBatchSize;
        this.maxBatchBinarySizeInBytes = maxBatchBinarySizeInBytes;
        this.itemSizeInBytesProvider = itemSizeInBytesProvider;
        this.doBatchAction = doBatchAction;
        this.currentBatch = null;
        this.batchFlushFuture = null;
    }

    /**
     * Submits an item for batch processing. The item will be added to the current batch or trigger batch processing if
     * limits are reached.
     *
     * @param input The item to process
     * @return A CompletableFuture that will be completed with the processing result
     */
    public CompletableFuture<Void> doAction(T input) {
        CompletableFuture<Void> outputFuture;
        Batch previousBatchToProcess = null;
        Batch newBatchToProcess = null;

        synchronized (currentBatchLock) {
            // If current batch can't fit this item, flush it first
            if (currentBatch != null && !currentBatch.canAcceptItem(input)) {
                previousBatchToProcess = getAndClearCurrentBatch();
            }

            // Create new batch if needed
            if (currentBatch == null) {
                currentBatch = new Batch();
                if (batchFlushFuture != null) {
                    cancelAndClearCurrentFlusher();
                }
            }

            outputFuture = currentBatch.addItem(input);

            // If batch is full, process it immediately
            if (!currentBatch.canAcceptMore()) {
                newBatchToProcess = getAndClearCurrentBatch();
            }

            // Set up timeout-based flushing for non-full batches
            if (currentBatch != null && batchFlushFuture == null) {
                batchFlushFuture = new CompletableFuture<>();
                batchFlushFuture
                        .completeOnTimeout(null, maxDelay.toMillis(), TimeUnit.MILLISECONDS)
                        .thenRun(() -> {
                            Batch toFlush;
                            synchronized (currentBatchLock) {
                                if (currentBatch != null) {
                                    toFlush = getAndClearCurrentBatch();
                                } else {
                                    return;
                                }
                            }
                            toFlush.processBatch();
                        });
            }

            // Clean up flush future if no current batch
            if (currentBatch == null && batchFlushFuture != null) {
                cancelAndClearCurrentFlusher();
            }
        }

        // Process batches outside of synchronized block to avoid blocking
        if (previousBatchToProcess != null) {
            previousBatchToProcess.processBatch();
        }

        if (newBatchToProcess != null) {
            newBatchToProcess.processBatch();
        }

        return outputFuture;
    }

    /** Gets the current batch and clears it, ensuring it's not null */
    private Batch getAndClearCurrentBatch() {
        if (currentBatch == null) {
            throw new IllegalStateException("currentBatch must not be null");
        }
        final Batch batchToProcess = currentBatch;
        currentBatch = null;
        return batchToProcess;
    }

    /** Cancels the current flush future and clears it, ensuring it's not null */
    private void cancelAndClearCurrentFlusher() {
        if (batchFlushFuture == null) {
            throw new IllegalStateException("batchFlushFuture must not be null");
        }
        batchFlushFuture.cancel(false);
        batchFlushFuture = null;
    }
}
