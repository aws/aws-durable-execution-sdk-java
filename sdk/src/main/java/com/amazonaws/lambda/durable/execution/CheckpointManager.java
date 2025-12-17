package com.amazonaws.lambda.durable.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;

import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Package-private checkpoint manager for batching and queueing checkpoint API
 * calls.
 * 
 * Single responsibility: Queue and batch checkpoint requests efficiently.
 * Uses CheckpointCallback to notify when checkpoints complete, avoiding cyclic
 * dependency.
 */
class CheckpointManager {
    private static final int MAX_BATCH_SIZE_BYTES = 750 * 1024; // 750KB
    private static final Logger logger = LoggerFactory.getLogger(CheckpointManager.class);

    private final CheckpointCallback callback;
    private final Supplier<String> tokenSupplier;
    private final String durableExecutionArn;
    private final DurableExecutionClient client;
    private final BlockingQueue<CheckpointRequest> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    record CheckpointRequest(OperationUpdate update, CompletableFuture<Void> completion) {
    }

    CheckpointManager(DurableExecutionClient client, ExecutorService executor, String durableExecutionArn,
            Supplier<String> tokenSupplier,
            CheckpointCallback callback) {
        this.client = client;
        this.executor = executor;
        this.durableExecutionArn = durableExecutionArn;
        this.tokenSupplier = tokenSupplier;
        this.callback = callback;
    }

    CompletableFuture<Void> checkpoint(OperationUpdate update) {
        logger.info("Checkpoint request received");
        var future = new CompletableFuture<Void>();
        queue.offer(new CheckpointRequest(update, future));

        if (isProcessing.compareAndSet(false, true)) {
            executor.submit(this::processQueue);
        }
        logger.info("Checkpoint request submitted");

        return future;
    }

    void shutdown() {
        var remaining = new ArrayList<CheckpointRequest>();
        queue.drainTo(remaining);
        remaining.forEach(
                req -> req.completion().completeExceptionally(new IllegalStateException("CheckpointManager shutdown")));
        executor.shutdown();
    }

    private void processQueue() {
        try {
            var batch = collectBatch();
            if (!batch.isEmpty()) {
                // Filter out null updates (empty checkpoints for polling)
                var updates = batch.stream()
                        .map(CheckpointRequest::update)
                        .filter(u -> u != null)
                        .toList();

                logger.debug("--- Making API call ---");
                var response = client.checkpoint(
                        durableExecutionArn,
                        tokenSupplier.get(),
                        updates);
                logger.debug("--- API call done ---");

                // Notify callback of completion
                callback.onComplete(
                        response.checkpointToken(),
                        response.newExecutionState().operations());

                logger.debug("--- After checkpoint ---");
                batch.forEach(req -> req.completion().complete(null));
            }
        } catch (Exception e) {
            var batch = new ArrayList<CheckpointRequest>();
            queue.drainTo(batch);
            batch.forEach(req -> req.completion().completeExceptionally(e));
        } finally {
            isProcessing.set(false);

            if (!queue.isEmpty() && isProcessing.compareAndSet(false, true)) {
                executor.submit(this::processQueue);
            }
        }
    }

    private List<CheckpointRequest> collectBatch() {
        var batch = new ArrayList<CheckpointRequest>();
        var currentSize = 0;

        CheckpointRequest req;
        while ((req = queue.poll()) != null) {
            var itemSize = estimateSize(req.update());

            if (currentSize + itemSize > MAX_BATCH_SIZE_BYTES && !batch.isEmpty()) {
                queue.offer(req);
                break;
            }

            batch.add(req);
            currentSize += itemSize;
        }

        return batch;
    }

    private int estimateSize(OperationUpdate update) {
        if (update == null) {
            return 0;
        }
        return update.id().length() +
                update.type().toString().length() +
                update.action().toString().length() +
                (update.payload() != null ? update.payload().length() : 0) +
                100;
    }
}
