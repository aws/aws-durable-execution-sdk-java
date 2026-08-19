// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class ConcurrencyOperationTest {

    private static final SerDes SER_DES = new JacksonSerDes();
    private static final String OPERATION_ID = "op-1";
    private static final String CHILD_OP_1 = TestUtils.hashOperationId(OPERATION_ID + "-1");
    private static final String CHILD_OP_2 = TestUtils.hashOperationId(OPERATION_ID + "-2");
    private static final TypeToken<Void> RESULT_TYPE = TypeToken.get(Void.class);

    private DurableContextImpl durableContext;
    private DurableContextImpl childContext;
    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContextImpl.class);
        executionManager = mock(ExecutionManager.class);

        var childContext = mock(DurableContextImpl.class);
        this.childContext = childContext;
        when(childContext.getExecutionManager()).thenReturn(executionManager);
        when(childContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .build());

        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .build());
        when(durableContext.createChildContext(anyString(), anyString(), anyBoolean()))
                .thenReturn(childContext);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("Root", ThreadType.CONTEXT));
        // All child operations are NOT in replay
        when(executionManager.getOperationAndUpdateReplayState(anyString())).thenReturn(null);
        // Simulate the real backend: the parent concurrency operation is available in storage after completion
        // so that waitForOperationCompletion() can find it. TestConcurrencyOperation.handleSuccess/Failure are no-ops
        // (no checkpoint sent), so we stub this unconditionally for OPERATION_ID.
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name("test-concurrency")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .build());
        when(executionManager.sendOperationUpdate(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private TestConcurrencyOperation createOperation(CompletionConfig completionConfig) throws Exception {
        return new TestConcurrencyOperation(
                OperationIdentifier.of(OPERATION_ID, "test-concurrency", OperationSubType.PARALLEL),
                RESULT_TYPE,
                SER_DES,
                durableContext,
                Integer.MAX_VALUE,
                completionConfig);
    }

    private void setOperationIdGenerator(ConcurrencyOperation<?> op, OperationIdGenerator mockGenerator)
            throws Exception {
        Field field = ConcurrencyOperation.class.getDeclaredField("operationIdGenerator");
        field.setAccessible(true);
        field.set(op, mockGenerator);
    }

    private CompletionConfig.CompletionDecision canComplete(
            ConcurrencyOperation<?> op, int succeededCount, int failedCount) throws Exception {
        var method = ConcurrencyOperation.class.getDeclaredMethod(
                "canComplete", int.class, int.class, ConcurrencyOperation.ExpectedCompletionStatus.class);
        method.setAccessible(true);
        try {
            return (CompletionConfig.CompletionDecision) method.invoke(op, succeededCount, failedCount, null);
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    // ===== Callback cycle tests =====

    @Test
    void allChildrenAlreadySucceed_callsHandleSuccess() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"result-1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_2))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_2)
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"result-2\"").build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var op = createOperation(CompletionConfig.allSuccessful());
        op.execute();
        op.enqueueItem(
                "branch-1",
                ctx1 -> {
                    functionCalled.set(true);
                    return "result-1";
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-2",
                ctx -> {
                    functionCalled.set(true);
                    return "result-2";
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        op.exposedJoin();

        assertTrue(op.isSuccessHandled());
        assertFalse(op.isFailureHandled());
        var items = op.getBranches();
        assertEquals(2, items.size());
        assertTrue(items.stream().allMatch(b -> b.getOperation().status().equals(OperationStatus.SUCCEEDED)));
        assertFalse(functionCalled.get(), "Functions should not be called during SUCCEEDED replay");
    }

    @Test
    void someChildrenSkipped_skippedChildrenNotExecuted() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.STARTED)
                        .build());
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_2))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_2)
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"result-2\"").build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var op = createOperation(CompletionConfig.minSuccessful(1));
        op.execute();
        op.enqueueItem(
                "branch-1",
                ctx1 -> {
                    functionCalled.set(true);
                    return "result-1";
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                true);
        op.enqueueItem(
                "branch-2",
                ctx -> {
                    functionCalled.set(true);
                    return "result-2";
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        op.exposedJoin();

        assertTrue(op.isSuccessHandled());
        assertFalse(op.isFailureHandled());
        var items = op.getBranches();
        assertEquals(2, items.size());
        assertFalse(items.get(0).isOperationCompleted());
        assertEquals(OperationStatus.STARTED, items.get(0).getOperation().status());
        assertEquals(OperationStatus.SUCCEEDED, items.get(1).getOperation().status());
        assertFalse(functionCalled.get(), "Functions should not be called during SUCCEEDED replay");
    }

    @Test
    void singleChildAlreadySucceeds_fullCycle() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("only-branch")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"done\"").build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var op = createOperation(CompletionConfig.minSuccessful(1));
        op.enqueueItem(
                "only-branch",
                ctx -> {
                    functionCalled.set(true);
                    return "done";
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        op.execute();
        op.exposedJoin();

        assertTrue(op.isSuccessHandled());
        var items = op.getBranches();
        assertEquals(1, items.size());
        assertEquals(OperationStatus.SUCCEEDED, items.get(0).getOperation().status());
        assertFalse(functionCalled.get(), "Function should not be called during SUCCEEDED replay");
    }

    @Test
    void canComplete_whenShouldCompleteReturnsNull_shouldThrow() throws Exception {
        var op = createOperation(CompletionConfig.shouldComplete(status -> null));

        var exception = assertThrows(NullPointerException.class, () -> canComplete(op, 0, 0));

        assertEquals("shouldComplete must return a completion decision", exception.getMessage());
    }

    @Test
    void coordinatorStartsNextQueuedChildAfterCompletionEvent() throws Exception {
        var activeCount = new AtomicInteger(0);
        var peakCount = new AtomicInteger(0);
        var startOrder = new CopyOnWriteArrayList<String>();
        var op = new QueueTestConcurrencyOperation(
                OperationIdentifier.of(OPERATION_ID, "test-concurrency", OperationSubType.PARALLEL),
                durableContext,
                childContext,
                activeCount,
                peakCount,
                startOrder);

        op.execute();
        op.enqueueItem(
                "branch-1",
                ctx -> "result-1",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-2",
                ctx -> "result-2",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        var first = op.getControlledChild(0);
        var second = op.getControlledChild(1);
        assertTrue(first.awaitStarted());
        assertFalse(second.hasStarted());

        first.completeSuccessfully();
        assertTrue(second.awaitStarted());
        second.completeSuccessfully();
        op.exposedJoin();

        assertTrue(op.isSuccessHandled());
        assertEquals(1, peakCount.get());
        assertEquals(List.of("branch-1", "branch-2"), startOrder);
    }

    @Test
    void exceptionalCompletionWakesWaitingCoordinator() throws Exception {
        var op = new QueueTestConcurrencyOperation(
                OperationIdentifier.of(OPERATION_ID, "test-concurrency", OperationSubType.PARALLEL),
                durableContext,
                childContext,
                new AtomicInteger(),
                new AtomicInteger(),
                new CopyOnWriteArrayList<>());

        op.execute();
        verify(executionManager, timeout(5_000)).deregisterActiveThread("Root");

        op.suspend();

        assertTrue(op.awaitCoordinatorStopped());
    }

    // ===== Test subclass =====

    static class TestConcurrencyOperation extends ConcurrencyOperation<Void> {

        private boolean successHandled = false;
        private boolean failureHandled = false;
        private final AtomicInteger executingCount = new AtomicInteger(0);
        private DurableContextImpl lastParentContext;

        TestConcurrencyOperation(
                OperationIdentifier operationIdentifier,
                TypeToken<Void> resultTypeToken,
                SerDes resultSerDes,
                DurableContextImpl durableContext,
                int maxConcurrency,
                CompletionConfig completionConfig) {
            super(
                    operationIdentifier,
                    resultTypeToken,
                    resultSerDes,
                    durableContext,
                    maxConcurrency,
                    completionConfig.completionDecisionFunction(),
                    NestingType.NESTED);
        }

        @Override
        protected void handleCompletion(CompletionConfig.CompletionDecision completionDecision) {
            successHandled = true;
            // Simulate the checkpoint ACK that a real subclass would receive after sendOperationUpdate.
            // This drives completionFuture to completion so waitForOperationCompletion() unblocks.
            onCheckpointComplete(Operation.builder()
                    .id(getOperationId())
                    .status(OperationStatus.SUCCEEDED)
                    .build());
        }

        @Override
        protected void start() {
            executeItems();
        }

        @Override
        protected void replay(Operation existing) {
            executeItems();
        }

        @Override
        public Void get() {
            return null;
        }

        void exposedJoin() {
            join();
        }

        int getExecutingCount() {
            return executingCount.get();
        }

        boolean isSuccessHandled() {
            return successHandled;
        }

        boolean isFailureHandled() {
            return failureHandled;
        }

        DurableContextImpl getLastParentContext() {
            return lastParentContext;
        }
    }

    static class QueueTestConcurrencyOperation extends ConcurrencyOperation<Void> {

        private final DurableContextImpl childContext;
        private final AtomicInteger activeCount;
        private final AtomicInteger peakCount;
        private final List<String> startOrder;
        private final List<ControlledChildOperation<?>> controlledChildren = new ArrayList<>();
        private volatile boolean successHandled;

        QueueTestConcurrencyOperation(
                OperationIdentifier operationIdentifier,
                DurableContextImpl durableContext,
                DurableContextImpl childContext,
                AtomicInteger activeCount,
                AtomicInteger peakCount,
                List<String> startOrder) {
            super(
                    operationIdentifier,
                    RESULT_TYPE,
                    SER_DES,
                    durableContext,
                    1,
                    CompletionConfig.allSuccessful().completionDecisionFunction(),
                    NestingType.NESTED);
            this.childContext = childContext;
            this.activeCount = activeCount;
            this.peakCount = peakCount;
            this.startOrder = startOrder;
        }

        @Override
        protected <R> ChildContextOperation<R> createItem(
                String operationId,
                String name,
                Function<DurableContext, R> function,
                TypeToken<R> resultType,
                SerDes serDes,
                OperationSubType branchSubType) {
            var child = new ControlledChildOperation<>(
                    OperationIdentifier.of(operationId, name, branchSubType),
                    resultType,
                    serDes,
                    childContext,
                    this,
                    activeCount,
                    peakCount,
                    startOrder);
            controlledChildren.add(child);
            return child;
        }

        @Override
        protected void handleCompletion(CompletionConfig.CompletionDecision completionDecision) {
            successHandled = true;
            onCheckpointComplete(Operation.builder()
                    .id(getOperationId())
                    .status(OperationStatus.SUCCEEDED)
                    .build());
        }

        @Override
        protected void start() {
            executeItems();
        }

        @Override
        protected void replay(Operation existing) {
            executeItems();
        }

        @Override
        public Void get() {
            return null;
        }

        ControlledChildOperation<?> getControlledChild(int index) {
            return controlledChildren.get(index);
        }

        void exposedJoin() {
            join();
        }

        boolean isSuccessHandled() {
            return successHandled;
        }

        void suspend() {
            completionFuture.completeExceptionally(new SuspendExecutionException());
        }

        boolean awaitCoordinatorStopped() throws Exception {
            getRunningUserHandler().get(5, TimeUnit.SECONDS);
            return getRunningUserHandler().isDone();
        }
    }

    static class ControlledChildOperation<R> extends ChildContextOperation<R> {

        private final AtomicInteger activeCount;
        private final AtomicInteger peakCount;
        private final List<String> startOrder;
        private final CountDownLatch started = new CountDownLatch(1);

        ControlledChildOperation(
                OperationIdentifier operationIdentifier,
                TypeToken<R> resultType,
                SerDes serDes,
                DurableContextImpl childContext,
                ConcurrencyOperation<?> parent,
                AtomicInteger activeCount,
                AtomicInteger peakCount,
                List<String> startOrder) {
            super(
                    operationIdentifier,
                    ctx -> null,
                    resultType,
                    RunInChildContextConfig.builder().serDes(serDes).build(),
                    childContext,
                    parent);
            this.activeCount = activeCount;
            this.peakCount = peakCount;
            this.startOrder = startOrder;
        }

        @Override
        public void execute() {
            startOrder.add(getName());
            var current = activeCount.incrementAndGet();
            peakCount.updateAndGet(peak -> Math.max(peak, current));
            started.countDown();
        }

        @Override
        public R get() {
            return null;
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        boolean hasStarted() {
            return started.getCount() == 0;
        }

        void completeSuccessfully() {
            activeCount.decrementAndGet();
            markAlreadyCompleted();
        }
    }
}
