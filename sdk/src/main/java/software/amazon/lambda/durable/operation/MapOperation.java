// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

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
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

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
    private boolean replayFromPayload;
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
                getToleratedFailureCount(config.completionConfig(), items.size()));
        if (config.completionConfig().minSuccessful() != null
                && config.completionConfig().minSuccessful() > items.size()) {
            throw new IllegalArgumentException("minSuccessful cannot be greater than total items: "
                    + config.completionConfig().minSuccessful() + " > " + items.size());
        }
        this.items = List.copyOf(items);
        this.function = function;
        this.itemResultType = itemResultType;
        this.serDes = config.serDes();

        addAllItems();
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
        sendOperationUpdateAsync(OperationUpdate.builder()
                .action(OperationAction.START)
                .subType(getSubType().getValue()));

        executeNextItemIfAllowed();
    }

    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED -> {
                if (existing.contextDetails() != null
                        && Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    // Large result: re-execute children to reconstruct MapResult
                    executeNextItemIfAllowed();
                } else {
                    // Small result: MapResult is in the payload, skip child replay
                    replayFromPayload = true;
                    markAlreadyCompleted();
                }
            }
            case STARTED -> {
                // Map was in progress when interrupted — re-create children without sending
                // another START (the backend rejects duplicate START for existing operations)
                executeNextItemIfAllowed();
            }
            default ->
                terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected map operation status: " + existing.status());
        }
    }

    private void addAllItems() {
        // Enqueue all items first, then start execution. This prevents early termination
        // criteria (e.g., minSuccessful) from completing the operation mid-loop on replay,
        // which would cause subsequent enqueue calls to fail with "completed operation".
        var branchPrefix = getName() == null ? "map-iteration-" : getName() + "-iteration-";
        for (int i = 0; i < items.size(); i++) {
            var index = i;
            var item = items.get(i);
            enqueueItem(
                    branchPrefix + i,
                    childCtx -> function.apply(item, index, childCtx),
                    itemResultType,
                    serDes,
                    OperationSubType.MAP_ITERATION);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void handleSuccess(ConcurrencyCompletionStatus concurrencyCompletionStatus) {
        var children = getBranches();
        var resultItems = new ArrayList<MapResult.MapResultItem<O>>(Collections.nCopies(items.size(), null));

        for (int i = 0; i < children.size(); i++) {
            var branch = (ChildContextOperation<O>) children.get(i);
            if (!branch.isOperationCompleted()) {
                resultItems.set(i, MapResult.MapResultItem.skipped());
            } else {
                try {
                    resultItems.set(i, MapResult.MapResultItem.succeeded(branch.get()));
                } catch (Exception e) {
                    resultItems.set(i, MapResult.MapResultItem.failed(MapResult.MapError.of(e)));
                }
            }
        }

        this.cachedResult = new MapResult<>(resultItems, concurrencyCompletionStatus);
        var serialized = serializeResult(cachedResult);
        var serializedBytes = serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (serializedBytes.length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(getSubType().getValue())
                    .payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + replayChildren flag
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(getSubType().getValue())
                    .payload("")
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    @Override
    public MapResult<O> get() {
        if (replayFromPayload) {
            // Small result replay: deserialize MapResult directly from checkpoint payload
            var op = waitForOperationCompletion();
            var result = (op.contextDetails() != null) ? op.contextDetails().result() : null;
            return deserializeResult(result);
        }
        // First execution or large result replay: wait for children, then aggregate
        join();
        return cachedResult;
    }
}
