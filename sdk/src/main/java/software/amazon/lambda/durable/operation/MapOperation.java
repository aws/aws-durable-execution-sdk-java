// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Executes a map operation: applies a function to each item in a collection concurrently, with each item running in its
 * own child context.
 *
 * <p>Extends {@link ConcurrencyOperation} following the same pattern as {@link ParallelOperation}. All branches are
 * created upfront in {@code start()}/{@code replay()}, and results are aggregated into a {@link MapResult} in
 * {@code get()}.
 *
 * @param <I> the input item type
 * @param <O> the output result type per item
 */
public class MapOperation<I, O> extends ConcurrencyOperation<MapResult<O>> {

    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    private final List<I> items;
    private final DurableContext.MapFunction<I, O> function;
    private final TypeToken<O> itemResultType;
    private final SerDes serDes;
    private volatile MapResult<O> cachedResult;

    public MapOperation(
            OperationIdentifier operationIdentifier,
            List<I> items,
            DurableContext.MapFunction<I, O> function,
            TypeToken<O> itemResultType,
            MapConfig config,
            DurableContextImpl durableContext) {
        super(
                operationIdentifier,
                new TypeToken<>() {},
                config.serDes(),
                durableContext,
                config.maxConcurrency(),
                config.completionConfig().minSuccessful(),
                getToleratedFailureCount(config.completionConfig(), items.size()),
                config.nestingType());
        if (config.completionConfig().minSuccessful() != null
                && config.completionConfig().minSuccessful() > items.size()) {
            throw new IllegalArgumentException("minSuccessful cannot be greater than total items: "
                    + config.completionConfig().minSuccessful() + " > " + items.size());
        }
        this.items = List.copyOf(items);
        this.function = function;
        this.itemResultType = itemResultType;
        this.serDes = config.serDes();
    }

    private void addAllItems() {
        addAllItemsWithStatus(Collections.nCopies(items.size(), null));
    }

    private void addAllItemsWithStatus(List<MapResult.MapResultItem.Status> resultItems) {
        // Enqueue all items first.
        // If the map is completed when replaying, mapResult != null and the items that have been skipped
        // will be skipped during replay.
        var branchPrefix = getName() == null ? "map-iteration-" : getName() + "-iteration-";
        for (int i = 0; i < items.size(); i++) {
            var index = i;
            var item = items.get(i);
            var status = resultItems.get(i);
            // the item will be skipped by ConcurrencyOperation if skip=true
            var skip = status == MapResult.MapResultItem.Status.SKIPPED;

            enqueueItem(
                    branchPrefix + i,
                    childCtx -> function.apply(item, index, childCtx),
                    itemResultType,
                    serDes,
                    OperationSubType.MAP_ITERATION,
                    skip);
        }
    }

    private static Integer getToleratedFailureCount(CompletionConfig completionConfig, int totalItems) {
        if (completionConfig == null
                || (completionConfig.toleratedFailureCount() == null
                        && completionConfig.toleratedFailurePercentage() == null)) {
            // neither toleratedFailureCount nor toleratedFailurePercentage is specified.
            return null;
        }
        int toleratedFailureCount = completionConfig.toleratedFailureCount() != null
                ? completionConfig.toleratedFailureCount()
                : Integer.MAX_VALUE;

        // convert percentage to count
        int toleratedFailureCountFromPercentage = completionConfig.toleratedFailurePercentage() != null
                ? (int) Math.floor(totalItems * completionConfig.toleratedFailurePercentage())
                : Integer.MAX_VALUE;
        // minimum of two if both count and percentage is specified
        return Math.min(toleratedFailureCount, toleratedFailureCountFromPercentage);
    }

    @Override
    protected void start() {
        if (items.isEmpty()) {
            markAlreadyCompleted();
            return;
        }
        sendOperationUpdateAsync(OperationUpdate.builder()
                .action(OperationAction.START)
                .subType(getSubType().getValue()));

        addAllItems();
        executeItems();
    }

    @Override
    protected void replay(Operation existing) {
        if (items.isEmpty()) {
            throw terminateExecutionWithIllegalDurableOperationException("Empty Map operation is not replayable");
        }
        switch (existing.status()) {
            case SUCCEEDED -> {
                var result = existing.contextDetails() != null
                        ? existing.contextDetails().result()
                        : null;
                var deserializedResult = result != null ? deserializeResult(result) : null;
                if (deserializedResult != null) {
                    addAllItemsWithStatus(deserializedResult.items().stream()
                            .map(MapResult.MapResultItem::status)
                            .toList());
                } else {
                    throw terminateExecutionWithIllegalDurableOperationException(
                            "Missing result in completed Map operation");
                }
                if (Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    // Large result: re-execute children to reconstruct MapResult
                    executeItems();
                } else {
                    // Small result: MapResult is in the payload, skip child replay
                    cachedResult = deserializedResult;
                    markAlreadyCompleted();
                }
            }
            case STARTED -> {
                // Map was in progress when interrupted — re-create children without sending
                // another START (the backend rejects duplicate START for existing operations)
                addAllItems();
                executeItems();
            }
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected map operation status: " + existing.status());
        }
    }

    @Override
    protected void handleCompletion(ConcurrencyCompletionStatus concurrencyCompletionStatus) {
        this.cachedResult = constructMapResult(concurrencyCompletionStatus, false);
        var serialized = serializeResult(cachedResult);
        var serializedBytes = serialized.getBytes(StandardCharsets.UTF_8);

        if (serializedBytes.length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(getSubType().getValue())
                    .payload(serialized));
        } else {
            // Large result: checkpoint with stripped payload + replayChildren flag
            var strippedResult = serializeResult(constructMapResult(concurrencyCompletionStatus, true));
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(getSubType().getValue())
                    .payload(strippedResult)
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    @SuppressWarnings("unchecked")
    private MapResult<O> constructMapResult(
            ConcurrencyCompletionStatus concurrencyCompletionStatus, boolean stripResult) {
        var children = getBranches();
        var resultItems = new ArrayList<MapResult.MapResultItem<O>>(Collections.nCopies(items.size(), null));

        for (int i = 0; i < children.size(); i++) {
            var branch = (ChildContextOperation<O>) children.get(i);
            if (!branch.isOperationCompleted()) {
                resultItems.set(i, MapResult.MapResultItem.skipped());
            } else {
                try {
                    resultItems.set(i, MapResult.MapResultItem.succeeded(stripResult ? null : branch.get()));
                } catch (Throwable exception) {
                    Throwable throwable = ExceptionHelper.unwrapCompletableFuture(exception);
                    if (throwable instanceof SuspendExecutionException suspendExecutionException) {
                        // Rethrow Error immediately — do not checkpoint
                        throw suspendExecutionException;
                    }
                    if (throwable
                            instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
                        // terminate the execution and throw the exception if it's not recoverable
                        throw terminateExecution(unrecoverableDurableExecutionException);
                    }
                    resultItems.set(
                            i, MapResult.MapResultItem.failed(stripResult ? null : MapResult.MapError.of(throwable)));
                }
            }
        }
        return new MapResult<>(resultItems, concurrencyCompletionStatus);
    }

    @Override
    public MapResult<O> get() {
        if (items.isEmpty()) {
            return MapResult.empty();
        }
        join();
        // cachedResult is always set upon successful completion
        return cachedResult;
    }
}
