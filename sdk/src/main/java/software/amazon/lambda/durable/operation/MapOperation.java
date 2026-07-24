// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(MapOperation.class);
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
                config.completionConfig().completionDecisionFunction(),
                config.nestingType());
        if (!config.completionConfig().hasCustomShouldComplete()
                && config.completionConfig().minSuccessful() != null
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
        addUnskippedItems(Collections.nCopies(items.size(), null));
    }

    private void addUnskippedItems(List<MapResult.MapResultItem.Status> resultItems) {
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

    @Override
    protected void start() {
        if (items.isEmpty()) {
            // TODO: Remove the checkpointEmptyMap flag and the non-checkpointing branch in a future major version,
            // making checkpointing the default.
            if (getContext().getDurableConfig().shouldCheckpointEmptyMap()) {
                // Checkpoint START + SUCCEED to produce a complete map operation with an empty result.
                sendOperationUpdate(OperationUpdate.builder()
                        .action(OperationAction.START)
                        .subType(getSubType().getValue()));
                handleCompletion(
                        CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.ALL_COMPLETED));
            } else {
                // Default: complete without checkpointing. Fire onOperationEnd so plugin hooks stay balanced.
                logger.warn(
                        "Empty map operation '{}' is not checkpointed by default. This behavior is unintended and may"
                                + " affect replay and plugin instrumentation. Enable"
                                + " DurableConfig.withCheckpointEmptyMap(true) to checkpoint empty maps.",
                        getName());
                cachedResult = MapResult.empty();
                fireOnOperationEnd(null, null, false);
                markAlreadyCompleted();
            }
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
        switch (existing.status()) {
            case SUCCEEDED -> {
                var result = existing.contextDetails() != null
                        ? existing.contextDetails().result()
                        : null;
                var deserializedResult = result != null ? deserializeResult(result) : null;
                if (deserializedResult != null) {
                    addUnskippedItems(deserializedResult.items().stream()
                            .map(MapResult.MapResultItem::status)
                            .toList());
                } else {
                    throw terminateExecutionWithIllegalDurableOperationException(
                            "Missing result in completed Map operation");
                }
                if (Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    // Large result: re-execute children to reconstruct MapResult
                    var expected = new ExpectedCompletionStatus(
                            deserializedResult.succeeded().size()
                                    + deserializedResult.failed().size(),
                            CompletionConfig.CompletionDecision.complete(deserializedResult.completionReason()));
                    executeItems(expected);
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
    protected void handleCompletion(CompletionConfig.CompletionDecision completionDecision) {
        this.cachedResult = constructMapResult(completionDecision);
        var serializedResult = serializeAndDeserializeResult(cachedResult);
        this.cachedResult = serializedResult.deserialized();
        var serializedBytes = serializedResult.serialized().getBytes(StandardCharsets.UTF_8);

        if (serializedBytes.length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(getSubType().getValue())
                    .payload(serializedResult.serialized()));
        } else {
            // Large result: checkpoint with stripped payload + replayChildren flag
            var strippedResult = serializeAndDeserializeResult(stripMapResult(cachedResult));
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(getSubType().getValue())
                    .payload(strippedResult.serialized())
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    private MapResult<O> stripMapResult(MapResult<O> result) {
        return new MapResult<>(
                result.items().stream()
                        .map(item -> new MapResult.MapResultItem<O>(item.status(), null, null))
                        .toList(),
                result.completionReason());
    }

    @SuppressWarnings("unchecked")
    private MapResult<O> constructMapResult(CompletionConfig.CompletionDecision completionDecision) {
        var children = getBranches();
        var resultItems = new ArrayList<MapResult.MapResultItem<O>>(Collections.nCopies(items.size(), null));

        for (int i = 0; i < children.size(); i++) {
            var branch = (ChildContextOperation<O>) children.get(i);
            if (!branch.isOperationCompleted()) {
                resultItems.set(i, MapResult.MapResultItem.skipped());
            } else {
                try {
                    resultItems.set(i, MapResult.MapResultItem.succeeded(branch.get()));
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
                    resultItems.set(i, MapResult.MapResultItem.failed(MapResult.MapError.of(throwable)));
                }
            }
        }
        return new MapResult<>(resultItems, completionDecision.completionStatus());
    }

    @Override
    public MapResult<O> get() {
        // Non-checkpointed empty map: no stored operation, so skip join() and return the result set in start().
        // Tied to the temporary checkpointEmptyMap flag; remove with it in a future major version.
        if (items.isEmpty() && !getContext().getDurableConfig().shouldCheckpointEmptyMap()) {
            return cachedResult;
        }
        join();
        // cachedResult is always set upon successful completion
        return cachedResult;
    }
}
