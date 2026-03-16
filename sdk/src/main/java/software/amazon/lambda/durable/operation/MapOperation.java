// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.MapConfig;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.MapResultItem;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Executes a map operation: applies a function to each item in a collection concurrently, with each item running in its
 * own child context.
 *
 * @param <I> the input item type
 * @param <O> the output result type per item
 */
public class MapOperation<I, O> extends BaseConcurrentOperation<MapResult<O>> {

    private final List<I> items;
    private final MapFunction<I, O> function;
    private final TypeToken<O> itemResultType;
    private final SerDes serDes;

    public MapOperation(
            String operationId,
            String name,
            List<I> items,
            MapFunction<I, O> function,
            TypeToken<O> itemResultType,
            MapConfig config,
            DurableContext durableContext) {
        super(
                operationId,
                name,
                OperationSubType.MAP,
                config.maxConcurrency(),
                config.completionConfig(),
                new TypeToken<>() {},
                config.serDes(),
                durableContext);
        this.items = List.copyOf(items);
        this.function = function;
        this.itemResultType = itemResultType;
        this.serDes = config.serDes();
    }

    @Override
    protected void startBranches() {
        for (int i = 0; i < items.size(); i++) {
            var index = i;
            var item = items.get(i);
            branchInternal("map-iteration-" + i, OperationSubType.MAP_ITERATION, itemResultType, serDes, childCtx -> {
                try {
                    return function.apply(item, index, childCtx);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * Waits for all branches to complete and aggregates results, then checkpoints the parent MAP operation.
     *
     * <p>Handles three cases:
     *
     * <ul>
     *   <li>Replay with small result (parent SUCCEEDED, no replayChildren): deserialize cached MapResult directly
     *   <li>Replay with large result (parent SUCCEEDED + replayChildren): aggregate from child replays, no
     *       re-checkpoint needed
     *   <li>First execution or STARTED replay: aggregate from branches, then checkpoint parent result
     * </ul>
     */
    @Override
    public MapResult<O> get() {
        // Check if parent operation already completed (replay with small result)
        if (isOperationCompleted()) {
            var op = getOperation();
            if (op != null && op.status() == OperationStatus.SUCCEEDED) {
                if (op.contextDetails() != null
                        && Boolean.TRUE.equals(op.contextDetails().replayChildren())) {
                    // Large result on replay: aggregate from child replays
                    return aggregateResults();
                }
                // Small result on replay: deserialize cached MapResult
                var result = (op.contextDetails() != null) ? op.contextDetails().result() : null;
                return deserializeResult(result);
            }
        }

        // First execution, STARTED replay, or SUCCEEDED+replayChildren replay: aggregate from branches
        var mapResult = aggregateResults();

        // Check if parent is already SUCCEEDED (replayChildren case) — skip re-checkpointing
        var existingOp = getOperation();
        if (existingOp == null || existingOp.status() != OperationStatus.SUCCEEDED) {
            // First execution or STARTED: checkpoint parent result from context thread (safe to .join() here)
            checkpointResult(mapResult);
        }

        return mapResult;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected MapResult<O> aggregateResults() {
        var branches = getBranches();
        var pendingQueue = getPendingQueue();
        var resultItems = new ArrayList<MapResultItem<O>>(Collections.nCopies(items.size(), null));

        for (int i = 0; i < branches.size(); i++) {
            var branch = (ChildContextOperation<O>) branches.get(i);
            // Skip branches still in the pending queue (never started due to early termination)
            if (pendingQueue.contains(branch)) {
                resultItems.set(i, MapResultItem.notStarted());
                continue;
            }
            try {
                resultItems.set(i, MapResultItem.success(branch.get()));
            } catch (Exception e) {
                resultItems.set(i, MapResultItem.failure(e));
            }
        }

        // Fill any remaining null slots (items beyond branches size) with notStarted
        for (int i = branches.size(); i < items.size(); i++) {
            resultItems.set(i, MapResultItem.notStarted());
        }

        var reason = getCompletionReason();
        if (reason == null) {
            reason = !pendingQueue.isEmpty() ? evaluateCompletionReason() : CompletionReason.ALL_COMPLETED;
        }
        return new MapResult<>(resultItems, reason);
    }
}
