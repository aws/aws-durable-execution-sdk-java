// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.CompletionConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.MapConfig;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.MapError;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.MapResultItem;
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
    private final MapFunction<I, O> function;
    private final TypeToken<O> itemResultType;
    private final SerDes serDes;
    private final CompletionConfig completionConfig;
    private boolean replayFromPayload;

    public MapOperation(
            OperationIdentifier operationIdentifier,
            List<I> items,
            MapFunction<I, O> function,
            TypeToken<O> itemResultType,
            MapConfig config,
            DurableContextImpl durableContext) {
        super(
                operationIdentifier,
                new TypeToken<>() {},
                config.serDes(),
                durableContext,
                config.maxConcurrency() != null ? config.maxConcurrency() : -1,
                config.completionConfig().minSuccessful() != null
                        ? config.completionConfig().minSuccessful()
                        : -1,
                config.completionConfig().toleratedFailureCount() != null
                        ? config.completionConfig().toleratedFailureCount()
                        : Integer.MAX_VALUE,
                config.completionConfig().toleratedFailurePercentage() != null
                        ? config.completionConfig().toleratedFailurePercentage()
                        : 100);
        this.items = List.copyOf(items);
        this.function = function;
        this.itemResultType = itemResultType;
        this.serDes = config.serDes();
        this.completionConfig = config.completionConfig();
    }

    @Override
    protected <R> ChildContextOperation<R> createItem(
            String operationId,
            String name,
            Function<DurableContext, R> function,
            TypeToken<R> resultType,
            SerDes serDes,
            DurableContextImpl parentContext) {
        return new ChildContextOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.MAP_ITERATION),
                function,
                resultType,
                serDes,
                parentContext,
                this);
    }

    @Override
    protected void start() {
        sendOperationUpdateAsync(OperationUpdate.builder()
                .action(OperationAction.START)
                .subType(getSubType().getValue()));
        addAllItems();
    }

    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED -> {
                if (existing.contextDetails() != null
                        && Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    // Large result: re-execute children to reconstruct MapResult
                    addAllItems();
                } else {
                    // Small result: MapResult is in the payload, skip child replay
                    replayFromPayload = true;
                    markAlreadyCompleted();
                }
            }
            case STARTED -> {
                // Map was in progress when interrupted — re-create children without sending
                // another START (the backend rejects duplicate START for existing operations)
                addAllItems();
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
        for (int i = 0; i < items.size(); i++) {
            var index = i;
            var item = items.get(i);
            enqueueItem(
                    "map-iteration-" + i, childCtx -> function.apply(item, index, childCtx), itemResultType, serDes);
        }
        startPendingItems();
    }

    @Override
    protected void handleSuccess() {
        checkpointMapResult();
    }

    @Override
    protected void handleFailure(ConcurrencyCompletionStatus concurrencyCompletionStatus) {
        checkpointMapResult();
    }

    @Override
    protected void validateItemCount() {
        if (completionConfig.minSuccessful() != null && completionConfig.minSuccessful() > getTotalItems()) {
            throw new IllegalArgumentException("minSuccessful (" + completionConfig.minSuccessful()
                    + ") exceeds the number of items (" + getTotalItems() + ")");
        }
    }

    /**
     * Overrides the default completion logic from {@link ConcurrencyOperation} to support Map's
     * {@link CompletionConfig} semantics. Unlike Parallel (where {@code minSuccessful == -1} means "all must succeed"),
     * Map's default {@code allCompleted()} allows failures without early termination.
     */
    @Override
    protected boolean canComplete() {
        int succeeded = getSucceededCount();
        int failed = getFailedCount();
        int totalCompleted = succeeded + failed;

        // Check minSuccessful
        if (completionConfig.minSuccessful() != null && succeeded >= completionConfig.minSuccessful()) {
            completionStatus = ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED;
            return true;
        }

        // Check toleratedFailureCount
        if (completionConfig.toleratedFailureCount() != null && failed > completionConfig.toleratedFailureCount()) {
            completionStatus = ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED;
            return true;
        }

        // Check toleratedFailurePercentage
        if (completionConfig.toleratedFailurePercentage() != null
                && totalCompleted > 0
                && ((double) failed / totalCompleted) > completionConfig.toleratedFailurePercentage()) {
            completionStatus = ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED;
            return true;
        }

        // All items finished (no pending, no running) — complete with ALL_COMPLETED
        if (isAllItemsFinished()) {
            completionStatus = ConcurrencyCompletionStatus.ALL_COMPLETED;
            return true;
        }

        return false;
    }

    private void checkpointMapResult() {
        var result = aggregateResults();
        var serialized = serializeResult(result);
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
        return aggregateResults();
    }

    /**
     * Aggregates results from completed branches into a {@code MapResult}.
     *
     * <p>Called after all branches have completed. At this point every branch's {@code completionFuture} is already
     * done, so {@code branch.get()} returns immediately without blocking.
     */
    @SuppressWarnings("unchecked")
    private MapResult<O> aggregateResults() {
        var children = getChildOperations();
        var resultItems = new ArrayList<MapResultItem<O>>(Collections.nCopies(items.size(), null));

        for (int i = 0; i < children.size(); i++) {
            var branch = (ChildContextOperation<O>) children.get(i);
            if (!branch.isOperationCompleted()) {
                resultItems.set(i, MapResultItem.notStarted());
                continue;
            }
            try {
                resultItems.set(i, MapResultItem.success(branch.get()));
            } catch (Exception e) {
                resultItems.set(i, MapResultItem.failure(buildMapError(e)));
            }
        }

        // Fill any remaining null slots (items beyond children size) with notStarted
        for (int i = children.size(); i < items.size(); i++) {
            resultItems.set(i, MapResultItem.notStarted());
        }

        return new MapResult<>(resultItems, toCompletionReason());
    }

    private CompletionReason toCompletionReason() {
        if (completionConfig.minSuccessful() != null && getSucceededCount() >= completionConfig.minSuccessful()) {
            return CompletionReason.MIN_SUCCESSFUL_REACHED;
        }
        if (completionConfig.toleratedFailureCount() != null
                && getFailedCount() > completionConfig.toleratedFailureCount()) {
            return CompletionReason.FAILURE_TOLERANCE_EXCEEDED;
        }
        if (completionConfig.toleratedFailurePercentage() != null && getFailedCount() > 0) {
            int total = getSucceededCount() + getFailedCount();
            if (total > 0 && ((double) getFailedCount() / total) > completionConfig.toleratedFailurePercentage()) {
                return CompletionReason.FAILURE_TOLERANCE_EXCEEDED;
            }
        }
        return CompletionReason.ALL_COMPLETED;
    }

    private static MapError buildMapError(Exception e) {
        return new MapError(
                e.getClass().getName(), e.getMessage(), ExceptionHelper.serializeStackTrace(e.getStackTrace()));
    }
}
