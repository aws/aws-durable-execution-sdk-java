// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.retry.PollingStrategies;
import software.amazon.lambda.durable.retry.PollingStrategy;
import software.amazon.lambda.durable.util.DurableApiErrorClassifier;

/**
 * Package-private checkpoint manager for batching and queueing checkpoint API calls.
 *
 * <p>Single responsibility: Queue and batch checkpoint requests efficiently. Uses a Consumer to notify when checkpoints
 * complete, avoiding cyclic dependency.
 */
class CheckpointManager {
    private static final int MAX_BATCH_SIZE_BYTES = 750 * 1024; // 750KB
    private static final int MAX_ITEM_COUNT = 200; // max updates in one batch
    private static final int FIRST_ATTEMPT = 1;
    private static final String REVOKED_CHECKPOINT_TOKEN_MESSAGE =
            "The checkpoint response did not include a checkpoint token";
    private static final Logger logger = LoggerFactory.getLogger(CheckpointManager.class);

    private final Consumer<List<Operation>> callback;
    private final String durableExecutionArn;
    private final Map<String, List<CompletableFuture<Operation>>> pollingFutures = new ConcurrentHashMap<>();
    private final ApiRequestDelayedBatcher<OperationUpdate> checkpointApiRequestDelayedBatcher;
    private final DurableConfig config;
    private final BooleanSupplier tryStartCheckpointProcessing;
    private final Runnable onCheckpointProcessingFinish;
    private final Runnable onTokenRevoked;
    private volatile String checkpointToken;
    private boolean pollingStopped; // Guarded by pollingFutures.
    /**
     * Set when a nonterminal checkpoint response omits its token. This invocation cannot checkpoint again, so
     * revocation is never cleared.
     */
    private volatile boolean checkpointTokenRevoked;

    CheckpointManager(
            DurableConfig config,
            String durableExecutionArn,
            String checkpointToken,
            Consumer<List<Operation>> callback) {
        this(config, durableExecutionArn, checkpointToken, callback, () -> true, () -> {}, () -> {});
    }

    CheckpointManager(
            DurableConfig config,
            String durableExecutionArn,
            String checkpointToken,
            Consumer<List<Operation>> callback,
            BooleanSupplier tryStartCheckpointProcessing,
            Runnable onCheckpointProcessingFinish,
            Runnable onTokenRevoked) {
        this.config = config;
        this.durableExecutionArn = durableExecutionArn;
        this.callback = callback;
        this.checkpointToken = checkpointToken;
        this.tryStartCheckpointProcessing = tryStartCheckpointProcessing;
        this.onCheckpointProcessingFinish = onCheckpointProcessingFinish;
        this.onTokenRevoked = onTokenRevoked;
        this.checkpointApiRequestDelayedBatcher = new ApiRequestDelayedBatcher<>(
                MAX_ITEM_COUNT, MAX_BATCH_SIZE_BYTES, CheckpointManager::estimateSize, this::checkpointBatch);
    }

    /** Returns {@code true} once the service has withdrawn this invocation's checkpoint token. */
    boolean isCheckpointTokenRevoked() {
        return checkpointTokenRevoked;
    }

    /**
     * Queues a checkpoint request for batched execution
     *
     * @return a future that completes when the checkpoint request is executed
     */
    CompletableFuture<Void> checkpoint(OperationUpdate update) {
        if (checkpointToken == null) {
            return CompletableFuture.failedFuture(new SuspendExecutionException(REVOKED_CHECKPOINT_TOKEN_MESSAGE));
        }

        logger.debug("Checkpoint request received: Action {}", update.action());
        var future = checkpointApiRequestDelayedBatcher.submit(update, config.getCheckpointDelay());
        // Withdrawal can flush before this request is submitted, so check again to avoid waiting for its timer.
        if (checkpointToken == null) {
            checkpointApiRequestDelayedBatcher.flush();
        }

        return future;
    }

    /**
     * Polls for updates of the specified operation with preconfigured intervals
     *
     * @return a future that completes when the operation is updated
     */
    CompletableFuture<Operation> pollForUpdate(String operationId) {
        return pollForUpdate(operationId, config.getPollingStrategy());
    }

    /**
     * Polls for updates of the specified operation at the specified time. If the give time is at the past, SDK will
     * immediately make a polling call.
     *
     * @param at the time to poll for the update
     * @return a future that completes when the operation is updated
     */
    CompletableFuture<Operation> pollForUpdate(String operationId, Instant at) {
        return pollForUpdate(operationId, PollingStrategies.at(at));
    }

    /**
     * Polls for updates of the specified operation with specified polling strategy
     *
     * @return a future that completes when the operation is updated
     */
    CompletableFuture<Operation> pollForUpdate(String operationId, PollingStrategy pollingStrategy) {
        logger.debug("Polling request received: operation id {}", operationId);
        var future = new CompletableFuture<Operation>();
        synchronized (pollingFutures) {
            if (pollingStopped) {
                return CompletableFuture.failedFuture(new SuspendExecutionException());
            }
            // register the future in pollingFutures, which will be completed by the polling thread
            pollingFutures
                    .computeIfAbsent(operationId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(future);
        }
        pollForUpdateInternal(future, FIRST_ATTEMPT, Instant.now(), pollingStrategy);
        return future;
    }

    /**
     * Recursively polls for updates of the specified operation with specified polling strategy
     *
     * @param future the future to complete
     * @param attempt the attempt number
     * @param startTime the start time of the current attempt
     * @param pollingStrategy the polling strategy
     * @return a completable future that completes when the polling is done
     */
    private CompletableFuture<Void> pollForUpdateInternal(
            CompletableFuture<Operation> future, int attempt, Instant startTime, PollingStrategy pollingStrategy) {

        // the delay is the polling interval minus the time already elapsed in the current attempt
        var delay = pollingStrategy.computeDelay(attempt).minus(Duration.between(startTime, Instant.now()));
        return checkpointApiRequestDelayedBatcher.submit(null, delay).thenCompose(v -> {
            if (future.isDone()) {
                return CompletableFuture.completedFuture(null);
            }
            var now = Instant.now();
            if (Duration.between(startTime, now).compareTo(pollingStrategy.computeDelay(attempt)) > 0) {
                // It has exceeded the previous attempt duration, starting a new attempt
                return pollForUpdateInternal(future, attempt + 1, now, pollingStrategy);
            } else {
                // continue the previous attempt. The future was completed just because
                // it was batched with other checkpoint API calls.
                return pollForUpdateInternal(future, attempt, startTime, pollingStrategy);
            }
        });
    }

    /** Cancels all polling futures and waits for all pending checkpoint requests to complete */
    void shutdown() {
        // complete all polling futures with an exception
        stopPolling(new IllegalStateException("CheckpointManager shutdown"));
        // wait for all non-polling checkpoint requests to complete
        checkpointApiRequestDelayedBatcher.shutdown();
    }

    private void stopPolling(Throwable cause) {
        List<List<CompletableFuture<Operation>>> allFutures;
        synchronized (pollingFutures) {
            pollingStopped = true;
            allFutures = new ArrayList<>(pollingFutures.values());
            pollingFutures.clear();
        }

        for (var futures : allFutures) {
            futures.forEach(future -> future.completeExceptionally(cause));
        }
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
            try {
                var response = config.getDurableExecutionClient()
                        .getExecutionState(durableExecutionArn, checkpointToken, nextMarker);
                logger.debug(
                        "Durable getExecutionState API called (latency={}ns): {}.",
                        System.nanoTime() - startTime,
                        response);
                operations.addAll(response.operations());
                nextMarker = response.nextMarker();
            } catch (AwsServiceException e) {
                throw DurableApiErrorClassifier.classifyException(e);
            }
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

            // An earlier batch already came back without a checkpoint token, so this invocation must not issue
            // further checkpoints.
            if (checkpointToken == null) {
                throw new SuspendExecutionException(REVOKED_CHECKPOINT_TOKEN_MESSAGE);
            }

            // Starting the backend request is coordinated with the last-thread suspension decision. Once suspension
            // wins that race, no later poll/checkpoint may advance backend state behind the PENDING response.
            if (!tryStartCheckpointProcessing.getAsBoolean()) {
                if (!request.isEmpty()) {
                    logger.error(
                            "Checkpoint invariant violation: skipping {} operation updates because execution has already"
                                    + " completed",
                            request.size());
                }
                logger.debug("Skipping checkpoint API call because execution has already completed");
                throw new SuspendExecutionException();
            }

            try {
                checkpointRequest(request);
            } catch (AwsServiceException e) {
                throw DurableApiErrorClassifier.classifyException(e);
            } finally {
                onCheckpointProcessingFinish.run();
            }
        }
    }

    private void checkpointRequest(List<OperationUpdate> request) {
        var startTime = System.nanoTime();
        logger.debug("Calling durable checkpoint API with {} updates: {}", request.size(), request);
        var response = config.getDurableExecutionClient().checkpoint(durableExecutionArn, checkpointToken, request);
        logger.debug("Durable checkpoint API called (latency={}ns): {}.", System.nanoTime() - startTime, response);

        checkpointToken = response.checkpointToken();
        if (checkpointToken == null) {
            handleMissingToken(request);
            return; // The token is gone, so this invocation cannot use the response state or fetch more pages.
        }

        var state = response.newExecutionState();
        if (state != null) {
            processUpdatedOperations(fetchAllPages(state));
        }
    }

    private void handleMissingToken(List<OperationUpdate> updates) {
        var cause = new SuspendExecutionException(REVOKED_CHECKPOINT_TOKEN_MESSAGE);
        stopPolling(cause);
        checkpointApiRequestDelayedBatcher.flush();

        // A terminal execution update was accepted, so only nonterminal requests require suspension.
        checkpointTokenRevoked = updates.stream().noneMatch(update -> update.type() == OperationType.EXECUTION);
        if (checkpointTokenRevoked) {
            logger.warn(
                    "The checkpoint response for durable execution {} did not include a checkpoint token. The SDK will"
                            + " stop checkpointing and report the invocation as PENDING.",
                    durableExecutionArn);
            onTokenRevoked.run();
            throw cause;
        }

        logger.info(
                "Checkpoint token withheld on durable execution {}'s terminal update; execution is finished.",
                durableExecutionArn);
    }

    private void processUpdatedOperations(List<Operation> operations) {
        var processStartTime = System.nanoTime();
        int completedFutures = 0;
        logger.debug("Processing {} operations. ({} pending pollers)", operations.size(), pollingFutures.size());
        callback.accept(operations);

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
