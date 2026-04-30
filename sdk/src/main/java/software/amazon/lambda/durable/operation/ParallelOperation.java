// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.List;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
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
public class ParallelOperation extends ConcurrencyOperation<ParallelResult> implements ParallelDurableFuture {

    // this field could be written and read in different threads
    private volatile ParallelResult cachedResult;
    private volatile ParallelResult partialResult;

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
                config.completionConfig().toleratedFailureCount(),
                config.nestingType());
    }

    @Override
    protected void handleCompletion(ConcurrencyCompletionStatus concurrencyCompletionStatus) {

        var items = List.copyOf(getBranches());
        var statuses = items.stream().map(this::getParallelItemStatus).toList();
        int succeededCount = Math.toIntExact(statuses.stream()
                .filter(s -> s == ParallelResult.Status.SUCCEEDED)
                .count());
        int failedCount = Math.toIntExact(
                statuses.stream().filter(s -> s == ParallelResult.Status.FAILED).count());
        int skippedCount = items.size() - succeededCount - failedCount;
        cachedResult = new ParallelResult(
                items.size(), succeededCount, failedCount, skippedCount, concurrencyCompletionStatus, statuses);

        // Branches added after checkpoint will not exist in the checkpointed result, but they'll be in the returned
        // value from get() method.
        sendOperationUpdate(OperationUpdate.builder()
                .action(OperationAction.SUCCEED)
                .subType(getSubType().getValue())
                .payload(serializeResult(cachedResult))
                .contextOptions(ContextOptions.builder().replayChildren(true).build()));
    }

    private ParallelResult.Status getParallelItemStatus(ChildContextOperation<?> childContextOperation) {
        if (!childContextOperation.isOperationCompleted()) {
            return ParallelResult.Status.SKIPPED;
        }
        try {
            childContextOperation.get();
            return ParallelResult.Status.SUCCEEDED;
        } catch (Throwable t) {
            return ParallelResult.Status.FAILED;
        }
    }

    private ParallelResult rebuildParallelResult() {
        if (cachedResult != null && cachedResult.size() != getBranches().size()) {
            return new ParallelResult(
                    getBranches().size(), // size might be updated after cached result is built
                    cachedResult.succeeded(),
                    cachedResult.failed(),
                    cachedResult.skipped(),
                    cachedResult.completionStatus(),
                    cachedResult.statuses());
        }
        return cachedResult;
    }

    @Override
    protected void start() {
        sendOperationUpdateAsync(OperationUpdate.builder()
                .action(OperationAction.START)
                .subType(getSubType().getValue()));

        executeItems();
    }

    @Override
    protected void replay(Operation existing) {
        // No-op: child branches handle their own replay via ChildContextOperation.replay().
        // Set replaying=true so handleSuccess() skips re-checkpointing the already-completed parallel context.
        if (ExecutionManager.isTerminalStatus(existing.status())) {
            // the operation is already completed, extract the branch completion status from the partialResult
            partialResult = existing.contextDetails() != null
                    ? deserializeResult(existing.contextDetails().result())
                    : null;
            if (partialResult != null) {
                var expected = new ExpectedCompletionStatus(
                        partialResult.succeeded() + partialResult.failed(), partialResult.completionStatus());
                executeItems(expected);
                return;
            }
        }
        executeItems();
    }

    @Override
    public ParallelResult get() {
        join();
        return rebuildParallelResult();
    }

    /** Calls {@link #get()} if not already called. Guarantees that the context is closed. */
    @Override
    public void close() {
        if (isJoined.get()) {
            return;
        }
        join();
    }

    public <T> DurableFuture<T> branch(
            String name, TypeToken<T> resultType, Function<DurableContext, T> func, ParallelBranchConfig config) {
        if (isJoined.get()) {
            throw new IllegalStateException("Cannot add branches after join() has been called");
        }

        var nextBranchIndex = getBranches().size();

        // ConcurrencyOperation will skip this branch if skip=true:
        // 1. if the parallel operation is already completed (partialResult is not null)
        // 2. if the branch is already skipped in the partialResult or nonexistent in the partialResult
        var skip = partialResult != null
                && (partialResult.statuses().size() <= nextBranchIndex
                        || partialResult.statuses().get(nextBranchIndex) == ParallelResult.Status.SKIPPED);
        var serDes = config.serDes() == null ? getContext().getDurableConfig().getSerDes() : config.serDes();
        return enqueueItem(name, func, resultType, serDes, OperationSubType.PARALLEL_BRANCH, skip);
    }
}
