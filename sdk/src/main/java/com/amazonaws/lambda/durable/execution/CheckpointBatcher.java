// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Package-private checkpoint manager for batching and queueing checkpoint API calls.
 *
 * <p>Single responsibility: Queue and batch checkpoint requests efficiently. Uses a Consumer to notify when checkpoints
 * complete, avoiding cyclic dependency.
 *
 * <p>Uses a dedicated SDK thread pool for internal coordination, keeping checkpoint processing separate from
 * customer-configured executors used for user-defined operations.
 *
 * @see InternalExecutor
 */
class CheckpointBatcher {
    private static final int MAX_BATCH_SIZE_BYTES = 750 * 1024; // 750KB
    private static final Logger logger = LoggerFactory.getLogger(CheckpointBatcher.class);

    private final Consumer<List<Operation>> callback;
    private final String durableExecutionArn;
    private final DurableExecutionClient client;
    private final BlockingQueue<CheckpointRequest> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private String checkpointToken;

    record CheckpointRequest(OperationUpdate update, CompletableFuture<Void> completion) {}

    CheckpointBatcher(
            DurableExecutionClient client,
            String durableExecutionArn,
            String checkpointToken,
            Consumer<List<Operation>> callback) {
        this.client = client;
        this.durableExecutionArn = durableExecutionArn;
        this.callback = callback;
        this.checkpointToken = checkpointToken;
    }

    CompletableFuture<Void> checkpoint(OperationUpdate update) {
        logger.debug(
                "Checkpoint request received: Action {}",
                update != null ? update.action() : "NULL (Checkpoint request)");
        var future = new CompletableFuture<Void>();
        queue.add(new CheckpointRequest(update, future));

        if (isProcessing.compareAndSet(false, true)) {
            InternalExecutor.INSTANCE.execute(this::processQueue);
        }

        return future;
    }

    void shutdown() {
        var remaining = new ArrayList<CheckpointRequest>();
        queue.drainTo(remaining);
        remaining.forEach(
                req -> req.completion().completeExceptionally(new IllegalStateException("CheckpointManager shutdown")));
    }

    public List<Operation> fetchAllPages(List<Operation> initialOperations, String nextMarker) {
        List<Operation> operations = new ArrayList<>();
        if (initialOperations != null) {
            operations.addAll(initialOperations);
        }
        while (nextMarker != null && !nextMarker.isEmpty()) {
            var response = client.getExecutionState(durableExecutionArn, checkpointToken, nextMarker);
            logger.debug("DAR getExecutionState called: {}.", response);
            operations.addAll(response.operations());
            nextMarker = response.nextMarker();
        }
        return operations;
    }

    private void processQueue() {
        try {
            var batch = collectBatch();
            if (!batch.isEmpty()) {
                // Filter out null updates (empty checkpoints for polling)
                var updates = batch.stream()
                        .map(CheckpointRequest::update)
                        .filter(Objects::nonNull)
                        .toList();

                var response = client.checkpoint(durableExecutionArn, checkpointToken, updates);
                logger.debug("DAR checkpointDurableExecution called: {}.", response);

                // Notify callback of completion
                // TODO: sam local backend returns no new execution state when called with zero
                // updates. WHY?
                // This means the polling will never receive an operation update and complete
                // the Phaser.
                checkpointToken = response.checkpointToken();
                if (response.newExecutionState() != null) {
                    var operations = fetchAllPages(
                            response.newExecutionState().operations(),
                            response.newExecutionState().nextMarker());
                    if (!operations.isEmpty()) {
                        callback.accept(operations);
                    }
                }

                batch.forEach(req -> req.completion().complete(null));
            }
        } catch (Exception e) {
            var batch = new ArrayList<CheckpointRequest>();
            queue.drainTo(batch);
            batch.forEach(req -> req.completion().completeExceptionally(e));
        } finally {
            isProcessing.set(false);

            if (!queue.isEmpty() && isProcessing.compareAndSet(false, true)) {
                InternalExecutor.INSTANCE.execute(this::processQueue);
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
                queue.add(req);
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
        return update.id().length()
                + update.type().toString().length()
                + update.action().toString().length()
                + (update.payload() != null ? update.payload().length() : 0)
                + 100;
    }
}
