// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.DurableConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Package-private checkpoint manager for batching and queueing checkpoint API calls.
 *
 * <p>Single responsibility: Queue and batch checkpoint requests efficiently. Uses a Consumer to notify when checkpoints
 * complete, avoiding cyclic dependency.
 */
class CheckpointBatcher {
    private static final int MAX_BATCH_SIZE_BYTES = 750 * 1024; // 750KB
    private static final int MAX_ITEM_COUNT = 100; // max updates in one batch
    private static final Logger logger = LoggerFactory.getLogger(CheckpointBatcher.class);

    private final Consumer<List<Operation>> callback;
    private final String durableExecutionArn;
    private final Map<String, List<CompletableFuture<Operation>>> pollingFutures = new ConcurrentHashMap<>();
    private final ApiRequestBatcher<OperationUpdate> checkpointApiRequestBatcher;
    private final DurableConfig config;
    private String checkpointToken;

    CheckpointBatcher(
            DurableConfig config,
            String durableExecutionArn,
            String checkpointToken,
            Consumer<List<Operation>> callback) {
        this.config = config;
        this.durableExecutionArn = durableExecutionArn;
        this.callback = callback;
        this.checkpointToken = checkpointToken;
        this.checkpointApiRequestBatcher = new ApiRequestBatcher<>(
                MAX_ITEM_COUNT, MAX_BATCH_SIZE_BYTES, CheckpointBatcher::estimateSize, this::checkpointBatch);
    }

    /** Queues a checkpoint request for batched execution */
    CompletableFuture<Void> checkpoint(OperationUpdate update) {
        logger.debug("Checkpoint request received: Action {}", update.action());
        return checkpointApiRequestBatcher.submit(update, config.getCheckpointDelay());
    }

    /** Polls for updates of the specified operation with preconfigured intervals */
    CompletableFuture<Operation> pollForUpdate(String operationId) {
        return pollForUpdate(operationId, config.getPollingInterval());
    }

    /** Polls for updates of the specified operation with specified delay */
    CompletableFuture<Operation> pollForUpdate(String operationId, Duration delay) {
        logger.debug("Polling request received: operation id {}", operationId);
        var future = new CompletableFuture<Operation>();
        synchronized (pollingFutures) {
            // register the future in pollingFutures, which will be completed by the polling thread
            pollingFutures
                    .computeIfAbsent(operationId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(future);
        }
        checkpointApiRequestBatcher.submit(null, delay).thenCompose(v -> {
            if (future.isDone()) {
                return CompletableFuture.completedFuture(null);
            }
            return checkpointApiRequestBatcher.submit(null, delay);
        });
        return future;
    }

    /** Cancels all polling futures and waits for all pending checkpoint requests to complete */
    void shutdown() {
        // complete all polling futures with an exception
        List<List<CompletableFuture<Operation>>> allFutures;
        synchronized (pollingFutures) {
            allFutures = new ArrayList<>(pollingFutures.values());
            pollingFutures.clear();
        }

        for (var futures : allFutures) {
            futures.forEach(f -> f.completeExceptionally(new IllegalStateException("CheckpointManager shutdown")));
        }

        // wait for all non-polling checkpoint requests to complete
        checkpointApiRequestBatcher.shutdown();
    }

    /**
     * Calling GetExecutionState API to get all pages of operations given CheckpointUpdatedExecutionState(operations,
     * nextMarker)
     */
    List<Operation> fetchAllPages(CheckpointUpdatedExecutionState checkpointUpdatedExecutionState) {
        List<Operation> operations = new ArrayList<>();
        if (checkpointUpdatedExecutionState == null) {
            return operations;
        }
        if (checkpointUpdatedExecutionState.operations() != null) {
            operations.addAll(checkpointUpdatedExecutionState.operations());
        }
        var nextMarker = checkpointUpdatedExecutionState.nextMarker();
        while (nextMarker != null && !nextMarker.isEmpty()) {
            var startTime = System.nanoTime();
            var response = config.getDurableExecutionClient()
                    .getExecutionState(durableExecutionArn, checkpointToken, nextMarker);
            logger.debug(
                    "Durable getExecutionState API called (latency={}ns): {}.",
                    System.nanoTime() - startTime,
                    response);
            operations.addAll(response.operations());
            nextMarker = response.nextMarker();
        }
        return operations;
    }

    private void checkpointBatch(List<OperationUpdate> updates) {
        synchronized (pollingFutures) {
            // filter the null values from pollers
            var request = updates.stream().filter(Objects::nonNull).toList();

            if (pollingFutures.isEmpty() && request.isEmpty()) {
                // ignore the batch if no pollers and no data to checkpoint
                return;
            }

            var startTime = System.nanoTime();
            logger.debug("Calling durable checkpoint API with {} updates: {}", updates.size(), request);
            var response = config.getDurableExecutionClient().checkpoint(durableExecutionArn, checkpointToken, request);
            logger.debug("Durable checkpoint API called (latency={}ns): {}.", System.nanoTime() - startTime, response);

            // Notify callback of completion
            checkpointToken = response.checkpointToken();
            if (response.newExecutionState() != null) {
                // fetch all pages of operations
                var operations = fetchAllPages(response.newExecutionState());

                var processStartTime = System.nanoTime();
                int completedFutures = 0;
                logger.debug(
                        "Processing {} operations. ({} pending pollers)", operations.size(), pollingFutures.size());
                // call the callback
                callback.accept(operations);

                // complete the registered pollingFutures
                for (var operation : operations) {
                    var pollers = pollingFutures.remove(operation.id());
                    if (pollers != null) {
                        completedFutures += pollers.size();
                        pollers.forEach(poller -> poller.complete(operation));
                    }
                }
                logger.debug(
                        "{} operations processed and {} pollers completed (latency={}ns). ",
                        operations.size(),
                        completedFutures,
                        System.nanoTime() - processStartTime);
            }
        }
    }

    private static int estimateSize(OperationUpdate update) {
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
