// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.CompletableFuture;
import software.amazon.lambda.durable.execution.ExecutionManager;

public final class MockExecutionManagerSupport {

    private MockExecutionManagerSupport() {}

    public static void completeOperationsOnManager(ExecutionManager executionManager) {
        doAnswer(invocation -> {
                    BaseDurableOperation operation = invocation.getArgument(0);
                    operation.getCompletionFuture().complete(operation);
                    return null;
                })
                .when(executionManager)
                .completeOperation(any(BaseDurableOperation.class));
    }

    /**
     * Minimal stub for operation tests that use a mocked ExecutionManager. Real wait synchronization semantics are
     * covered by ExecutionManagerTest.
     */
    public static void stubWaitForOperationCompletion(ExecutionManager executionManager) {
        completeOperationsOnManager(executionManager);
        doAnswer(invocation -> {
                    BaseDurableOperation operation = invocation.getArgument(0);
                    executionManager.validateCurrentThreadCanWaitForDurableOperation(
                            operation.getType(), operation.getName());
                    var threadContext = executionManager.getCurrentThreadContext();
                    var completionFuture = operation.getCompletionFuture();
                    CompletableFuture<?> future = completionFuture;
                    if (!completionFuture.isDone()) {
                        future = completionFuture.thenRun(
                                () -> executionManager.registerActiveThread(threadContext.threadId()));
                        executionManager.deregisterActiveThread(threadContext.threadId());
                    }
                    future.join();
                    return null;
                })
                .when(executionManager)
                .waitForOperationCompletion(any(BaseDurableOperation.class));
    }
}
