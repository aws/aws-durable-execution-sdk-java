// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.NestingType;
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
                OperationIdentifier.of(
                        OPERATION_ID, "test-concurrency", OperationType.CONTEXT, OperationSubType.PARALLEL),
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
                OperationSubType.PARALLEL_BRANCH);
        op.enqueueItem(
                "branch-2",
                ctx -> {
                    functionCalled.set(true);
                    return "result-2";
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH);

        op.exposedJoin();

        assertTrue(op.isSuccessHandled());
        assertFalse(op.isFailureHandled());
        var items = op.getBranches();
        assertEquals(2, items.size());
        assertTrue(items.stream().allMatch(b -> b.getOperation().status().equals(OperationStatus.SUCCEEDED)));
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
                OperationSubType.PARALLEL_BRANCH);

        op.execute();
        op.exposedJoin();

        assertTrue(op.isSuccessHandled());
        var items = op.getBranches();
        assertEquals(1, items.size());
        assertEquals(OperationStatus.SUCCEEDED, items.get(0).getOperation().status());
        assertFalse(functionCalled.get(), "Function should not be called during SUCCEEDED replay");
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
                    completionConfig.minSuccessful(),
                    completionConfig.toleratedFailureCount(),
                    NestingType.NESTED);
        }

        @Override
        protected void handleCompletion(ConcurrencyCompletionStatus completionStatus) {
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
}
