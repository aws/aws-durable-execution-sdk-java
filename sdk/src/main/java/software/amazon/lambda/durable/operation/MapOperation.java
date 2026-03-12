// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.MapConfig;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.BatchResult;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Executes a map operation: applies a function to each item in a collection concurrently, with each item running in its
 * own child context.
 *
 * @param <I> the input item type
 * @param <O> the output result type per item
 */
public class MapOperation<I, O> extends BaseConcurrentOperation<BatchResult<O>> {

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
                    return function.apply(childCtx, item, index);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * Waits for all branches to complete and aggregates results. Overrides the base class get() to directly wait on
     * each branch rather than relying on the parent operation's completion future, which avoids thread coordination
     * issues between the checkpoint processing thread and the context thread.
     */
    @Override
    @SuppressWarnings("unchecked")
    public BatchResult<O> get() {
        var branches = getBranches();
        var results = new ArrayList<O>(Collections.nCopies(items.size(), null));
        var errors = new ArrayList<Throwable>(Collections.nCopies(items.size(), null));

        for (int i = 0; i < branches.size(); i++) {
            var branch = (ChildContextOperation<O>) branches.get(i);
            try {
                results.set(i, branch.get());
            } catch (Exception e) {
                errors.set(i, e);
            }
        }

        var reason = getCompletionReason() != null ? getCompletionReason() : CompletionReason.ALL_COMPLETED;
        return new BatchResult<>(results, errors, reason);
    }

    @Override
    protected BatchResult<O> aggregateResults() {
        // Not used — get() handles aggregation directly
        throw new UnsupportedOperationException("aggregateResults should not be called directly");
    }
}
