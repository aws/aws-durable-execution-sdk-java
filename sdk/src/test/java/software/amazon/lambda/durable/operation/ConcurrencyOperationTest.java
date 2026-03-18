// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.Executors;
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
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class ConcurrencyOperationTest {

    private static final SerDes SER_DES = new JacksonSerDes();
    private static final String OPERATION_ID = "op-1";
    private static final TypeToken<Void> RESULT_TYPE = TypeToken.get(Void.class);

    private DurableContextImpl durableContext;
    private DurableContextImpl childContext;
    private ExecutionManager executionManager;
    private AtomicInteger operationIdCounter;
    private OperationIdGenerator mockIdGenerator;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContextImpl.class);
        executionManager = mock(ExecutionManager.class);
        operationIdCounter = new AtomicInteger(0);

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
        when(durableContext.createChildContext(anyString(), anyString())).thenReturn(childContext);
        when(durableContext.createChildContextWithoutSettingThreadContext(anyString(), anyString()))
                .thenReturn(childContext);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("Root", ThreadType.CONTEXT));
        mockIdGenerator = mock(OperationIdGenerator.class);
        when(mockIdGenerator.nextOperationId()).thenAnswer(inv -> "child-" + operationIdCounter.incrementAndGet());
        // All child operations are NOT in replay
        when(executionManager.getOperationAndUpdateReplayState(anyString())).thenReturn(null);
    }

    private TestConcurrencyOperation createOperation(int maxConcurrency, int minSuccessful, int toleratedFailureCount)
            throws Exception {
        TestConcurrencyOperation testConcurrencyOperation = new TestConcurrencyOperation(
                OperationIdentifier.of(
                        OPERATION_ID, "test-concurrency", OperationType.CONTEXT, OperationSubType.PARALLEL),
                RESULT_TYPE,
                SER_DES,
                durableContext,
                maxConcurrency,
                minSuccessful,
                toleratedFailureCount);
        setOperationIdGenerator(testConcurrencyOperation, mockIdGenerator);
        return testConcurrencyOperation;
    }

    private void setOperationIdGenerator(ConcurrencyOperation<?> op, OperationIdGenerator mockGenerator)
            throws Exception {
        Field field = ConcurrencyOperation.class.getDeclaredField("operationIdGenerator");
        field.setAccessible(true);
        field.set(op, mockGenerator);
    }

    // ===== Callback cycle tests =====

    @Test
    void allChildrenAlreadySucceed_callsHandleSuccess() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("child-1"))
                .thenReturn(Operation.builder()
                        .id("child-1")
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"result-1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState("child-2"))
                .thenReturn(Operation.builder()
                        .id("child-2")
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"result-2\"").build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var op = createOperation(-1, -1, 0);
        op.addItem(
                "branch-1",
                ctx -> {
                    functionCalled.set(true);
                    return "result-1";
                },
                TypeToken.get(String.class),
                SER_DES);
        op.addItem(
                "branch-2",
                ctx -> {
                    functionCalled.set(true);
                    return "result-2";
                },
                TypeToken.get(String.class),
                SER_DES);

        runJoin(op);

        assertTrue(op.isSuccessHandled());
        assertFalse(op.isFailureHandled());
        assertEquals(2, op.getSucceededCount());
        assertEquals(0, op.getFailedCount());
        assertFalse(functionCalled.get(), "Functions should not be called during SUCCEEDED replay");
    }

    @Test
    void singleChildAlreadySucceeds_fullCycle() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("child-1"))
                .thenReturn(Operation.builder()
                        .id("child-1")
                        .name("only-branch")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"done\"").build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var op = createOperation(-1, 1, 0);
        op.addItem(
                "only-branch",
                ctx -> {
                    functionCalled.set(true);
                    return "done";
                },
                TypeToken.get(String.class),
                SER_DES);

        runJoin(op);

        assertTrue(op.isSuccessHandled());
        assertEquals(1, op.getSucceededCount());
        assertEquals(0, op.getFailedCount());
        assertFalse(functionCalled.get(), "Function should not be called during SUCCEEDED replay");
    }

    @Test
    void addItem_usesRootChildContextAsParent() throws Exception {
        var op = createOperation(-1, -1, 0);

        op.addItem("branch-1", ctx -> "result", TypeToken.get(String.class), SER_DES);

        // rootContext is created via durableContext.createChildContext(...) in the constructor,
        // so the parentContext passed to createItem must be that child context, not durableContext itself
        assertNotSame(durableContext, op.getLastParentContext());
        assertSame(childContext, op.getLastParentContext());
    }

    // ===== Helpers =====

    private void runJoin(TestConcurrencyOperation op) throws InterruptedException {
        var t = new Thread(op::exposedJoin);
        t.start();
        t.join(2000);
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
            lastParentContext = parentContext;
            return new ChildContextOperation<R>(
                    OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.PARALLEL_BRANCH),
                    function,
                    resultType,
                    serDes,
                    parentContext,
                    this) {
                @Override
                public void execute() {
                    executingCount.incrementAndGet();
                    super.execute();
                }
            };
        }

        @Override
        protected void handleSuccess() {
            successHandled = true;
        }

        @Override
        protected void handleFailure(ConcurrencyCompletionStatus completionStatus) {
            failureHandled = true;
        }

        @Override
        protected void start() {}

        @Override
        protected void replay(Operation existing) {}

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
}
