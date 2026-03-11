// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.ConcurrencyConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableParallelFuture;
import software.amazon.lambda.durable.ParallelResult;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

public class ParallelOperation extends BaseConcurrentOperation<ParallelResult> implements DurableParallelFuture {

    public ParallelOperation(String operationId, String name, ConcurrencyConfig config, DurableContext durableContext) {
        super(operationId, name, OperationSubType.PARALLEL_BRANCH, config, durableContext);
    }

    public <T> DurableFuture<T> branch(
            String name, TypeToken<T> resultType, SerDes resultSerDes, Function<DurableContext, T> func) {
        return branchInternal(name, resultType, resultSerDes, func);
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        sendOperationUpdateAsync(
                OperationUpdate.builder().action(OperationAction.START).subType(OperationSubType.PARALLEL.getValue()));
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        // always replay the branches

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
    public ParallelResult get() {
        // wait for all to complete
        waitForOperationCompletion();

        // This method only returns stats of the branches (succeeded, failed, etc)
        // Users need to use each branch to check for the result or error.
        return new ParallelResult();
    }
}
