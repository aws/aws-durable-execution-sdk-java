// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import com.amazonaws.lambda.durable.DurableConfig;
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
                MAX_ITEM_COUNT, MAX_BATCH_SIZE_BYTES, CheckpointBatcher::estimateSize, this::doBatchAction);
    }

    CompletableFuture<Void> checkpoint(OperationUpdate update) {
        logger.debug("Checkpoint request received: Action {}", update.action());
        return checkpointApiRequestBatcher.submit(update, config.getCheckpointDelay());
    }

    CompletableFuture<Operation> pollForUpdate(String operationId) {
        logger.debug("Polling request received: operation id {}", operationId);
        var future = new CompletableFuture<Operation>();
        synchronized (pollingFutures) {
            // register the future in pollingFutures, which will be completed by the polling thread
            pollingFutures
                    .computeIfAbsent(operationId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(future);
        }
        checkpointApiRequestBatcher.submit(null, config.getPollingInterval());
        return future;
    }

    void shutdown() {
        List<List<CompletableFuture<Operation>>> allFutures;
        checkpointApiRequestBatcher.shutdown();

        synchronized (pollingFutures) {
            allFutures = new ArrayList<>(pollingFutures.values());
            pollingFutures.clear();
        }

        for (var futures : allFutures) {
            futures.forEach(f -> f.completeExceptionally(new IllegalStateException("CheckpointManager shutdown")));
        }
    }

    public List<Operation> fetchAllPages(List<Operation> initialOperations, String nextMarker) {
        List<Operation> operations = new ArrayList<>();
        if (initialOperations != null) {
            operations.addAll(initialOperations);
        }
        while (nextMarker != null && !nextMarker.isEmpty()) {
            var response = config.getDurableExecutionClient()
                    .getExecutionState(durableExecutionArn, checkpointToken, nextMarker);
            logger.debug("Durable API getExecutionState called: {}.", response);
            operations.addAll(response.operations());
            nextMarker = response.nextMarker();
        }
        return operations;
    }

    private void doBatchAction(List<OperationUpdate> updates) {
        // doBatchAction can be called concurrently from ApiRequestBatcher.
        synchronized (pollingFutures) {
            if (pollingFutures.isEmpty() && updates.isEmpty()) {
                return;
            }

            // filter the null values from pollers
            var request = updates.stream().filter(Objects::nonNull).toList();

            logger.debug("Calling durable API checkpointDurableExecution with {} updates", request.size());
            var response = config.getDurableExecutionClient().checkpoint(durableExecutionArn, checkpointToken, request);
            logger.debug("Durable API checkpointDurableExecution called: {}.", response);

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

                // complete the registered pollingFutures
                for (var operation : operations) {
                    var pollers = pollingFutures.remove(operation.id());
                    if (pollers != null) {
                        pollers.forEach(poller -> poller.complete(operation));
                    }
                }
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
