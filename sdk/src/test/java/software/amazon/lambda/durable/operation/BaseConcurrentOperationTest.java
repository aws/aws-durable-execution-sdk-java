// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/** Unit tests for BaseConcurrentOperation. */
class BaseConcurrentOperationTest {

    private static final JacksonSerDes SERDES = new JacksonSerDes();
    private static final OperationIdentifier OP_ID =
            OperationIdentifier.of("op-1", "test-parallel", OperationType.CONTEXT, OperationSubType.PARALLEL);

    private DurableContext durableContext;
    private ExecutionManager executionManager;
    private DurableContext childContext;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContext.class);
        executionManager = mock(ExecutionManager.class);
        childContext = mock(DurableContext.class);

        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(durableContext.getDurableConfig()).thenReturn(createConfig());
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("Root", ThreadType.CONTEXT));
        when(durableContext.createChildContext(anyString(), anyString())).thenReturn(childContext);
        when(childContext.getExecutionManager()).thenReturn(executionManager);
        when(childContext.getDurableConfig()).thenReturn(createConfig());
        when(executionManager.sendOperationUpdate(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private DurableConfig createConfig() {
        return DurableConfig.builder()
                .withExecutorService(Executors.newCachedThreadPool())
                .build();
    }

    // ===== Concrete test subclass =====

    /**
     * Minimal concrete subclass that completes when all branches succeed. Tracks isDone and markOperationAsCompleted
     * calls for verification.
     */
    static class TestConcurrentOperation extends BaseConcurrentOperation<String> {
        private final AtomicInteger isDoneCalls = new AtomicInteger(0);
        private final AtomicInteger markCompletedCalls = new AtomicInteger(0);
        int lastSucceeded;
        int lastFailed;
        int totalBranches;

        TestConcurrentOperation(int maxConcurrency, int totalBranches, DurableContext durableContext) {
            super(OP_ID, TypeToken.get(String.class), SERDES, maxConcurrency, durableContext);
            this.totalBranches = totalBranches;
        }

        /** Expose branchInternal for testing. */
        <T> ChildContextOperation<T> addBranch(
                String name, TypeToken<T> resultType, SerDes resultSerDes, Function<DurableContext, T> func) {
            return branchInternal(name, resultType, resultSerDes, func);
        }

        @Override
        protected boolean isDone(int succeeded, int failed) {
            isDoneCalls.incrementAndGet();
            lastSucceeded = succeeded;
            lastFailed = failed;
            // done when all branches have completed (succeeded + failed == total branches)
            return (succeeded + failed) >= totalBranches;
        }

        @Override
        protected void markOperationAsCompleted(int succeeded, int failed) {
            markCompletedCalls.incrementAndGet();
            lastSucceeded = succeeded;
            lastFailed = failed;
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
        public String get() {
            waitForOperationCompletion();
            return "";
        }
    }

    // ===== start() sends START update =====

    @Test
    void startSendsOperationUpdate() {
        // first execution — no existing operation
        when(executionManager.getOperationAndUpdateReplayState("op-1")).thenReturn(null);

        var operation = new TestConcurrentOperation(10, 0, durableContext);
        operation.execute();

        verify(executionManager)
                .sendOperationUpdate(
                        argThat(update -> update.action().toString().equals("START")
                                && update.id().equals("op-1")
                                && update.name().equals("test-parallel")));
    }

    // ===== replay SUCCEEDED without replayChildren marks completed =====

    @Test
    void replaySucceededMarksAlreadyCompleted() {
        when(executionManager.getOperationAndUpdateReplayState("op-1"))
                .thenReturn(Operation.builder()
                        .id("op-1")
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(ContextDetails.builder().build())
                        .build());

        var operation = new TestConcurrentOperation(10, 0, durableContext);
        operation.execute();

        assertTrue(operation.isOperationCompleted());
    }

    // ===== replay FAILED without replayChildren marks completed =====

    @Test
    void replayFailedMarksAlreadyCompleted() {
        when(executionManager.getOperationAndUpdateReplayState("op-1"))
                .thenReturn(Operation.builder()
                        .id("op-1")
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.FAILED)
                        .contextDetails(ContextDetails.builder().build())
                        .build());

        var operation = new TestConcurrentOperation(10, 0, durableContext);
        operation.execute();

        assertTrue(operation.isOperationCompleted());
    }

    // ===== replay SUCCEEDED with replayChildren does NOT mark completed =====

    @Test
    void replaySucceededWithReplayChildrenDoesNotMarkCompleted() {
        when(executionManager.getOperationAndUpdateReplayState("op-1"))
                .thenReturn(Operation.builder()
                        .id("op-1")
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().replayChildren(true).build())
                        .build());

        var operation = new TestConcurrentOperation(10, 0, durableContext);
        operation.execute();

        assertFalse(operation.isOperationCompleted(), "Should not be completed — branches need to re-execute");
    }

    // ===== replay STARTED does not mark completed =====

    @Test
    void replayStartedDoesNotMarkCompleted() {
        when(executionManager.getOperationAndUpdateReplayState("op-1"))
                .thenReturn(Operation.builder()
                        .id("op-1")
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.STARTED)
                        .build());

        var operation = new TestConcurrentOperation(10, 0, durableContext);
        operation.execute();

        assertFalse(operation.isOperationCompleted(), "STARTED replay should wait for branches");
    }

    // ===== branchInternal adds to branches list =====

    @Test
    void branchInternalAddsToBranchesList() {
        when(executionManager.getOperationAndUpdateReplayState("op-1")).thenReturn(null);
        // branch operations will also look up their own operation
        when(executionManager.getOperationAndUpdateReplayState("branch-1")).thenReturn(null);
        when(executionManager.getOperationAndUpdateReplayState("branch-2")).thenReturn(null);

        var operation = new TestConcurrentOperation(10, 2, durableContext);
        operation.execute();

        operation.addBranch("b1", TypeToken.get(String.class), SERDES, ctx -> "r1");
        operation.addBranch("b2", TypeToken.get(String.class), SERDES, ctx -> "r2");

        assertEquals(2, operation.getBranches().size());
    }

    // ===== branchInternal does not execute when shouldExecuteChildBranches is false (replay SUCCEEDED) =====

    @Test
    void branchInternalSkipsExecutionOnCompletedReplay() {
        when(executionManager.getOperationAndUpdateReplayState("op-1"))
                .thenReturn(Operation.builder()
                        .id("op-1")
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(ContextDetails.builder().build())
                        .build());

        var operation = new TestConcurrentOperation(10, 1, durableContext);
        operation.execute();

        // Adding a branch after replay SUCCEEDED should not trigger execution
        var branch = operation.addBranch("b1", TypeToken.get(String.class), SERDES, ctx -> "result");

        // The branch should be added to the list but not executed
        assertEquals(1, operation.getBranches().size());
        // branch-1 should NOT have been looked up in executionManager (no execute() call)
        verify(executionManager, never()).getOperationAndUpdateReplayState("branch-1");
    }

    // ===== concurrency limiting: maxConcurrency=1 executes branches sequentially =====

    @Test
    void maxConcurrencyOneExecutesBranchesSequentially() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("op-1")).thenReturn(null);
        when(executionManager.getOperationAndUpdateReplayState("branch-1")).thenReturn(null);
        when(executionManager.getOperationAndUpdateReplayState("branch-2")).thenReturn(null);
        when(executionManager.hasOperationsForContext("branch-1")).thenReturn(false);
        when(executionManager.hasOperationsForContext("branch-2")).thenReturn(false);
        when(childContext.createChildContext(anyString(), anyString())).thenReturn(childContext);

        var concurrentCount = new AtomicInteger(0);
        var maxConcurrent = new AtomicInteger(0);

        var operation = new TestConcurrentOperation(1, 2, durableContext);
        operation.execute();

        operation.addBranch("b1", TypeToken.get(String.class), SERDES, ctx -> {
            var current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, current));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrentCount.decrementAndGet();
            return "r1";
        });

        operation.addBranch("b2", TypeToken.get(String.class), SERDES, ctx -> {
            var current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, current));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrentCount.decrementAndGet();
            return "r2";
        });
        Thread.sleep(1000);

        assertEquals(1, maxConcurrent.get(), "Only 1 branch should run at a time with maxConcurrency=1");
    }

    // ===== onChildContextComplete increments failed counter on exception =====

    @Test
    void onChildContextCompleteIncrementsFailedOnException() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("op-1")).thenReturn(null);
        when(executionManager.getOperationAndUpdateReplayState("branch-1")).thenReturn(null);
        when(executionManager.hasOperationsForContext("branch-1")).thenReturn(false);
        when(childContext.createChildContext(anyString(), anyString())).thenReturn(childContext);

        var operation = new TestConcurrentOperation(10, 1, durableContext) {};
        operation.execute();

        var b1 = operation.addBranch("b1", TypeToken.get(String.class), SERDES, ctx -> {
            throw new RuntimeException("branch failed");
        });

        Thread.sleep(1000); // improve
        b1.markAlreadyCompleted();

        assertEquals(0, operation.lastSucceeded);
        assertEquals(1, operation.lastFailed);
    }

    // ===== onChildContextComplete is no-op after shouldExecuteChildBranches is false =====

    @Test
    @SuppressWarnings("unchecked")
    void onChildContextCompleteIgnoredWhenShouldExecuteIsFalse() {
        when(executionManager.getOperationAndUpdateReplayState("op-1"))
                .thenReturn(Operation.builder()
                        .id("op-1")
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(ContextDetails.builder().build())
                        .build());

        var operation = new TestConcurrentOperation(10, 0, durableContext);
        operation.execute();

        // Simulate a late callback from a child — should be ignored
        var mockChild = mock(ChildContextOperation.class);
        operation.onChildContextComplete(mockChild);

        // get() should never be called on the child since the callback is ignored
        verify(mockChild, never()).get();
        assertEquals(0, operation.isDoneCalls.get());
    }

    // ===== replay with unexpected status terminates execution =====

    @Test
    void replayUnexpectedStatusTerminatesExecution() {
        when(executionManager.getOperationAndUpdateReplayState("op-1"))
                .thenReturn(Operation.builder()
                        .id("op-1")
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.PENDING)
                        .build());

        var operation = new TestConcurrentOperation(10, 0, durableContext);

        // Should terminate execution for unexpected status
        assertThrows(Exception.class, operation::execute);
    }
}
