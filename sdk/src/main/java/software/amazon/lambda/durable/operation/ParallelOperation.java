// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.ConcurrencyExecutionException;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Manages parallel execution of multiple branches as child context operations.
 *
 * <p>Extends {@link ConcurrencyOperation} to provide parallel-specific behavior:
 *
 * <ul>
 *   <li>Creates branches as {@link ChildContextOperation} with {@link OperationSubType#PARALLEL_BRANCH}
 *   <li>Checkpoints SUCCESS/FAIL on the parallel context when completion criteria are met
 *   <li>Throws {@link ConcurrencyExecutionException} when the operation fails
 * </ul>
 *
 * <p>Context hierarchy:
 *
 * <pre>
 * DurableContext (root)
 *   └── ParallelOperation context (ChildContextOperation with PARALLEL subtype)
 *         ├── Branch 1 context (ChildContextOperation with PARALLEL_BRANCH)
 *         ├── Branch 2 context (ChildContextOperation with PARALLEL_BRANCH)
 *         └── Branch N context (ChildContextOperation with PARALLEL_BRANCH)
 * </pre>
 *
 * @param <T> the result type of this operation (typically Void)
 */
public class ParallelOperation<T> extends ConcurrencyOperation<T> {

    public ParallelOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContext durableContext,
            int maxConcurrency,
            int minSuccessful,
            int toleratedFailureCount) {
        super(
                operationIdentifier,
                resultTypeToken,
                resultSerDes,
                durableContext,
                maxConcurrency,
                minSuccessful,
                toleratedFailureCount);
    }

    @Override
    protected <R> ChildContextOperation<R> createItem(
            String operationId,
            String name,
            Function<DurableContext, R> function,
            TypeToken<R> resultType,
            SerDes serDes,
            DurableContext parentContext) {
        return new ChildContextOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.PARALLEL_BRANCH),
                function,
                resultType,
                serDes,
                parentContext,
                this);
    }

    @Override
    protected void handleSuccess() {
        sendOperationUpdate(OperationUpdate.builder()
                .action(OperationAction.SUCCEED)
                .subType(getSubType().getValue()));
    }

    @Override
    protected void handleFailure(ConcurrencyCompletionStatus concurrencyCompletionStatus) {
        sendOperationUpdate(OperationUpdate.builder()
                .action(OperationAction.FAIL)
                .subType(getSubType().getValue())
                .error(ErrorObject.builder()
                        .errorMessage("Parallel operation failed with " + concurrencyCompletionStatus + " status")
                        .build()));
    }

    @Override
    protected void start() {
        sendOperationUpdateAsync(OperationUpdate.builder()
                .action(OperationAction.START)
                .subType(getSubType().getValue()));
    }

    @Override
    protected void replay(Operation existing) {
        // Always replay child branches for parallel
        start();
    }

    @Override
    public T get() {
        // TODO: implement proper return value handling
        join();
        return null;
    }
}
