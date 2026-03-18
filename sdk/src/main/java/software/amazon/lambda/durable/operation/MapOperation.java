// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import software.amazon.lambda.durable.MapConfig;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.MapResultItem;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

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
            DurableContextImpl durableContext) {
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
                return function.apply(item, index, childCtx);
            });
        }
    }

    /**
     * Aggregates results from completed branches into a {@code MapResult}.
     *
     * <p>Called from {@link BaseConcurrentOperation#onChildContextComplete} on the checkpoint processing thread after
     * all branches have completed. At this point every branch's {@code completionFuture} is already done, so
     * {@code branch.get()} returns immediately without blocking.
     */
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
                resultItems.set(i, MapResultItem.failure(ExceptionHelper.buildErrorObject(e, serDes)));
            }
        }

        // Fill any remaining null slots (items beyond branches size) with notStarted
        for (int i = branches.size(); i < items.size(); i++) {
            resultItems.set(i, MapResultItem.notStarted());
        }

        var reason = getCompletionReason();
        if (reason == null) {
            reason = CompletionReason.ALL_COMPLETED;
        }
        return new MapResult<>(resultItems, reason);
    }
}
