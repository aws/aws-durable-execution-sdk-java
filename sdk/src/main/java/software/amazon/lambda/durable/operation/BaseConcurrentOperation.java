// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.ConcurrencyConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.NoopSerDes;
import software.amazon.lambda.durable.serde.SerDes;

public abstract class BaseConcurrentOperation<R> extends BaseDurableOperation<R> {

    private final ArrayList<ChildContextOperation<?>> branches;
    private final Queue<ChildContextOperation<?>> queue;
    private final DurableContext rootContext;
    private final AtomicInteger succeeded;
    private final AtomicInteger failed;
    private final OperationSubType subType;
    private final ConcurrencyConfig config;
    private final AtomicInteger activeBranches;

    public BaseConcurrentOperation(
            String operationId,
            String name,
            OperationSubType subType,
            ConcurrencyConfig config,
            DurableContext durableContext) {
        super(operationId, name, OperationType.CONTEXT, new TypeToken<>() {}, new NoopSerDes(), durableContext);
        this.branches = new ArrayList<>();
        this.queue = new ConcurrentLinkedQueue<>();
        this.rootContext = durableContext.createChildContext(operationId, name);
        this.config = config;
        this.succeeded = new AtomicInteger(0);
        this.failed = new AtomicInteger(0);
        this.subType = subType;
        this.activeBranches = new AtomicInteger(0);
    }

    protected <T> ChildContextOperation<T> branchInternal(
            String name, TypeToken<T> resultType, SerDes resultSerDes, Function<DurableContext, T> func) {
        var operationId = this.rootContext.nextOperationId();
        ChildContextOperation<T> operation;

        synchronized (this.branches) {
            operation = new ChildContextOperation<>(
                    operationId,
                    name,
                    func,
                    OperationSubType.PARALLEL_BRANCH,
                    resultType,
                    resultSerDes,
                    rootContext,
                    this);
            branches.add(operation);
            queue.add(operation);
        }

        executeNewBranchIfConcurrencyAllows();

        return operation;
    }

    private void executeNewBranchIfConcurrencyAllows() {
        synchronized (this) {
            // use one extra thread from user's thread pool to wait for the semaphore
            if (activeBranches.get() < config.maxConcurrency()) {
                if (!queue.isEmpty()) {
                    activeBranches.incrementAndGet();

                    var op = queue.poll();
                    op.execute();
                }
            }
        }
    }

    @Override
    public <T> void onChildContextComplete(ChildContextOperation<T> parallelBranchOperation) {
        if (isOperationCompleted()) {
            return;
        }

        activeBranches.decrementAndGet();

        // handle branch results
        try {
            parallelBranchOperation.get();
            succeeded.incrementAndGet();
        } catch (Exception e) {
            failed.incrementAndGet();
        }

        if (isDone()) {
            sendOperationUpdateAsync(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(OperationSubType.PARALLEL.getValue())
                    .payload(""));

            rootContext.close();
        } else {
            // we must make sure the thread for the new branch is registered before the child thread is deregistered
            executeNewBranchIfConcurrencyAllows();
        }
    }

    private boolean isDone() {
        return succeeded.get() >= config.minSuccessful() || failed.get() > config.toleratedFailureCount();
    }
}
