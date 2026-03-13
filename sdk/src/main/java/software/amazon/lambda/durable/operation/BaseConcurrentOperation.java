// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Base class for concurrent operations like {@link ParallelOperation} and {@link MapOperation}.
 *
 * @param <T> the type of the operation's result (BatchResult for MapOperation or ParallelResult for ParallelOperation)
 */
public abstract class BaseConcurrentOperation<T> extends BaseDurableOperation<T> {
    private final int maxConcurrency;
    private final ArrayList<ChildContextOperation<?>> branches;
    private final Queue<ChildContextOperation<?>> pendingBranches;
    private final DurableContext thisContext; // parent context of branches
    private final AtomicInteger succeeded;
    private final AtomicInteger failed;
    private final AtomicInteger activeBranches;
    private final AtomicBoolean shouldExecuteChildBranches;
    private final OperationIdGenerator operationIdGenerator;

    public BaseConcurrentOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            int maxConcurrency,
            DurableContext durableContext) {
        super(operationIdentifier, resultTypeToken, resultSerDes, durableContext);
        this.maxConcurrency = maxConcurrency;
        this.activeBranches = new AtomicInteger(0);
        this.branches = new ArrayList<>();
        this.pendingBranches = new ConcurrentLinkedQueue<>();
        this.thisContext = durableContext.createChildContext(getOperationId(), getName());
        this.succeeded = new AtomicInteger(0);
        this.failed = new AtomicInteger(0);
        this.shouldExecuteChildBranches = new AtomicBoolean(true);
        this.operationIdGenerator = new OperationIdGenerator(operationIdentifier.operationId());
    }

    @Override
    protected void start() {
        // start the child context operation for Parallel/Map
        sendOperationUpdateAsync(OperationUpdate.builder().action(OperationAction.START));
    }

    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED, FAILED -> {
                if (existing.contextDetails() != null
                        && Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    // re-execute child branches to reconstruct result
                } else {
                    shouldExecuteChildBranches.set(false);
                    markAlreadyCompleted();
                }
            }
            case STARTED -> {
                // wait for branches to be added
            }
            default -> {
                // unexpected status which should not happen
                terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected parallel/map status: " + existing.status());
            }
        }
    }

    protected <U> ChildContextOperation<U> branchInternal(
            String name, TypeToken<U> resultType, SerDes resultSerDes, Function<DurableContext, U> func) {
        var operationId = operationIdGenerator.nextOperationId();
        ChildContextOperation<U> operation = new ChildContextOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.PARALLEL_BRANCH),
                func,
                resultType,
                resultSerDes,
                thisContext,
                this);

        branches.add(operation);
        pendingBranches.add(operation);
        if (shouldExecuteChildBranches.get()) {
            executeNewBranchIfConcurrencyAllows();
        }

        return operation;
    }

    private void executeNewBranchIfConcurrencyAllows() {
        // use one extra thread from user's thread pool to wait for the semaphore
        while (activeBranches.get() < maxConcurrency && !pendingBranches.isEmpty()) {
            ChildContextOperation<?> op = null;
            // synchronized block to ensure activeBranches and queue are updated atomically
            synchronized (this) {
                if (activeBranches.get() < maxConcurrency && !pendingBranches.isEmpty()) {
                    activeBranches.incrementAndGet();
                    op = pendingBranches.poll();
                }
            }
            if (op != null) {
                op.execute();
            }
        }
    }

    public <U> void onChildContextComplete(ChildContextOperation<U> childOperation) {
        if (!shouldExecuteChildBranches.get()) {
            // the execution of child branches is already done, ignore the callback
            return;
        }

        activeBranches.decrementAndGet();

        // handle branch results
        try {
            childOperation.get();
            succeeded.incrementAndGet();
        } catch (Throwable e) {
            failed.incrementAndGet();
        }

        if (isDone(succeeded.get(), failed.get())) {
            shouldExecuteChildBranches.set(true);
            // mark this operation SUCCEEDED or FAILED
            markOperationAsCompleted(succeeded.get(), failed.get());
        } else {
            // we must make sure the thread for the new branch is registered before the child thread is deregistered
            executeNewBranchIfConcurrencyAllows();
        }
    }

    /**
     * Gets the list of branches in the concurrent operation.
     *
     * @return the list of branches in the concurrent operation
     */
    protected ArrayList<ChildContextOperation<?>> getBranches() {
        return branches;
    }

    /**
     * Checks if the concurrent operation is done based on the number of succeeded and failed branches.
     *
     * @param succeeded the number of succeeded branches
     * @param failed the number of failed branches
     * @return true if the concurrent operation is done (the completion condition is met)
     */
    protected abstract boolean isDone(int succeeded, int failed);

    /**
     * Marks the concurrent operation as completed (SUCCEEDED or FAILED) based on the number of succeeded and failed
     * branches and checkpoint the result.
     *
     * @param succeeded the number of succeeded branches
     * @param failed the number of failed branches
     */
    protected abstract void markOperationAsCompleted(int succeeded, int failed);
}
