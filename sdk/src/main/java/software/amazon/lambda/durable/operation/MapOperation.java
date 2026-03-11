// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.SequencedCollection;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.BatchResult;
import software.amazon.lambda.durable.ConcurrencyConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

public class MapOperation<T, I> extends BaseConcurrentOperation<BatchResult<T>> {
    private final MapFunction<I, T> func;
    private final ArrayList<ChildContextOperation<T>> iterations;
    private final SequencedCollection<I> collection;
    private final SerDes serDes;
    private final TypeToken<T> branchResultTypeToken;

    public MapOperation(
            String operationId,
            String name,
            SequencedCollection<I> collection,
            MapFunction<I, T> func,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            ConcurrencyConfig config,
            DurableContext durableContext) {
        super(operationId, name, OperationSubType.MAP_ITERATION, config, durableContext);
        this.func = func;
        this.iterations = new ArrayList<>();
        this.branchResultTypeToken = resultTypeToken;
        this.serDes = resultSerDes;
        this.collection = collection;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        sendOperationUpdateAsync(
                OperationUpdate.builder().action(OperationAction.START).subType(OperationSubType.MAP.getValue()));
        for (var item : collection) {
            int index = iterations.size();
            iterations.add(branchInternal(
                    getName() + "-iteration-" + index,
                    branchResultTypeToken,
                    serDes,
                    (ctx) -> func.apply(ctx, item, index)));
        }
    }

    /**
     * Replays the operation.
     *
     * @param existing
     */
    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED, FAILED -> markAlreadyCompleted();
            case STARTED -> start();
        }
    }

    /**
     * Blocks until the operation completes and returns the result.
     *
     * <p>This delegates to operation.get() which handles: - Thread deregistration (allows suspension) - Thread
     * reactivation (resumes execution) - Result retrieval
     *
     * @return the operation result
     */
    @Override
    public BatchResult<T> get() {
        waitForOperationCompletion();
        // build the batch results
        //
        return new BatchResult<>();
    }
}
