// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
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

    private boolean replaying = false;

    public ParallelOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext,
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
            DurableContextImpl parentContext) {
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
        if (replaying) {
            // Do not send checkpoint during replay
            return;
        }
        sendOperationUpdate(OperationUpdate.builder()
                .action(OperationAction.SUCCEED)
                .subType(getSubType().getValue())
                .contextOptions(ContextOptions.builder().replayChildren(true).build()));
    }

    @Override
    protected void handleFailure(ConcurrencyCompletionStatus concurrencyCompletionStatus) {
        handleSuccess();
    }

    @Override
    protected void start() {
        sendOperationUpdateAsync(OperationUpdate.builder()
                .action(OperationAction.START)
                .subType(getSubType().getValue()));
    }

    @Override
    protected void replay(Operation existing) {
        // No-op: child branches handle their own replay via ChildContextOperation.replay().
        // Set replaying=true so handleSuccess() skips re-checkpointing the already-completed parallel context.
        replaying = true;
    }

    @Override
    public T get() {
        // TODO: implement proper return value handling
        join();
        return null;
    }
}
