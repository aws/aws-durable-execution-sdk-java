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
import software.amazon.lambda.durable.ParallelConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Manages parallel execution of multiple branches as child context operations.
 *
 * <p>Extends {@link ConcurrencyOperation} to provide parallel-specific behavior:
 *
 * <ul>
 *   <li>Creates branches as {@link ChildContextOperation} with {@link OperationSubType#PARALLEL_BRANCH}
 *   <li>Checkpoints SUCCESS on the parallel context when completion criteria are met
 *   <li>Returns a {@link ParallelResult} summarising branch outcomes
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
 */
public class ParallelOperation extends ConcurrencyOperation<ParallelResult> {

    private boolean skipCheckpoint = false;

    public ParallelOperation(
            OperationIdentifier operationIdentifier,
            SerDes resultSerDes,
            DurableContextImpl durableContext,
            ParallelConfig config) {
        super(
                operationIdentifier,
                TypeToken.get(ParallelResult.class),
                resultSerDes,
                durableContext,
                config.maxConcurrency(),
                config.completionConfig().minSuccessful(),
                config.completionConfig().toleratedFailureCount());
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
    protected void handleSuccess(ConcurrencyCompletionStatus concurrencyCompletionStatus) {
        if (skipCheckpoint) {
            // Do not send checkpoint during replay
            markAlreadyCompleted();
            return;
        }
        sendOperationUpdate(OperationUpdate.builder()
                .action(OperationAction.SUCCEED)
                .subType(getSubType().getValue())
                .contextOptions(ContextOptions.builder().replayChildren(true).build()));
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
        skipCheckpoint = ExecutionManager.isTerminalStatus(existing.status());
    }

    @Override
    public ParallelResult get() {
        join();
        return new ParallelResult(getTotalItems(), getSucceededCount(), getFailedCount(), getCompletionStatus());
    }
}
