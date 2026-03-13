// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
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

class ConcurrencyOperationTest {

    private static final SerDes SER_DES = new JacksonSerDes();
    private static final String OPERATION_ID = "op-1";
    private static final TypeToken<Void> RESULT_TYPE = TypeToken.get(Void.class);

    private DurableContext durableContext;
    private ExecutionManager executionManager;
    private AtomicInteger operationIdCounter;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContext.class);
        executionManager = mock(ExecutionManager.class);
        operationIdCounter = new AtomicInteger(0);

        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .build());
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("Root", ThreadType.CONTEXT));
        when(durableContext.nextOperationId()).thenAnswer(inv -> "child-" + operationIdCounter.incrementAndGet());
    }

    private OperationIdentifier createOpId() {
        return OperationIdentifier.of(
                OPERATION_ID, "test-concurrency", OperationType.CONTEXT, OperationSubType.PARALLEL);
    }

    /**
     * Concrete test subclass of ConcurrencyOperation that tracks handleSuccess/handleFailure calls and uses stub child
     * operations for testing concurrency control logic.
     */
    private TestConcurrencyOperation createOperation(int maxConcurrency, int minSuccessful, int toleratedFailureCount) {
        return new TestConcurrencyOperation(
                createOpId(),
                RESULT_TYPE,
                SER_DES,
                durableContext,
                maxConcurrency,
                minSuccessful,
                toleratedFailureCount);
    }

    // ===== canComplete() tests =====

    @Test
    void canCompleteWhenMinSuccessfulReached() {
        var op = createOperation(-1, 2, 0);
        // Add 3 items, simulate 2 succeeding
        addStubItems(op, 3);
        simulateItemCompletions(op, 2, 0);

        assertTrue(op.exposedCanComplete());
    }

    @Test
    void canCompleteWhenFailureToleranceExceeded() {
        var op = createOperation(-1, -1, 1);
        addStubItems(op, 3);
        // 2 failures exceeds toleratedFailureCount of 1
        simulateItemCompletions(op, 0, 2);

        assertTrue(op.exposedCanComplete());
    }

    @Test
    void cannotCompleteWhenStillInProgress() {
        var op = createOperation(-1, 2, 1);
        addStubItems(op, 4);
        // 1 succeeded, 1 failed — still possible to reach minSuccessful=2
        simulateItemCompletions(op, 1, 1);

        assertFalse(op.exposedCanComplete());
    }

    @Test
    void canCompleteWhenNotEnoughRemainingToReachMinSuccessful() {
        var op = createOperation(-1, 3, 0);
        addStubItems(op, 4);
        // 1 succeeded, 2 failed — only 1 remaining, can't reach minSuccessful=3
        simulateItemCompletions(op, 1, 2);

        assertTrue(op.exposedCanComplete());
    }

    @Test
    void canCompleteAllSucceededWithMinSuccessfulAll() {
        var op = createOperation(-1, -1, 0);
        addStubItems(op, 3);
        // minSuccessful=-1 means all must succeed; first failure triggers completion
        simulateItemCompletions(op, 2, 1);

        assertTrue(op.exposedCanComplete());
    }

    // ===== validateItemCount() tests =====

    @Test
    void validateItemCountThrowsWhenMinSuccessfulExceedsBranchCount() {
        var op = createOperation(-1, 5, 0);
        addStubItems(op, 3);

        assertThrows(IllegalArgumentException.class, op::exposedValidateItemCount);
    }

    @Test
    void validateItemCountPassesWhenMinSuccessfulEqualsBranchCount() {
        var op = createOperation(-1, 3, 0);
        addStubItems(op, 3);

        assertDoesNotThrow(op::exposedValidateItemCount);
    }

    @Test
    void validateItemCountPassesWhenMinSuccessfulIsAll() {
        var op = createOperation(-1, -1, 0);
        addStubItems(op, 5);

        assertDoesNotThrow(op::exposedValidateItemCount);
    }

    @Test
    void validateItemCountPassesWithZeroBranches() {
        var op = createOperation(-1, -1, 0);
        // No items added — minSuccessful=-1 resolves to 0 items, which is fine
        assertDoesNotThrow(op::exposedValidateItemCount);
    }

    // ===== maxConcurrency tests =====

    @Test
    void maxConcurrencyLimitsRunningItems() {
        var op = createOperation(2, -1, 0);
        // Add 4 items — only 2 should start immediately
        addStubItems(op, 4);

        assertEquals(2, op.getStartedCount(), "Only maxConcurrency items should start");
    }

    @Test
    void unlimitedConcurrencyStartsAllItems() {
        var op = createOperation(-1, -1, 0);
        addStubItems(op, 5);

        assertEquals(5, op.getStartedCount(), "All items should start with unlimited concurrency");
    }

    @Test
    void maxConcurrencyOfOneStartsOneItem() {
        var op = createOperation(1, -1, 0);
        addStubItems(op, 3);

        assertEquals(1, op.getStartedCount(), "Only 1 item should start with maxConcurrency=1");
    }

    // ===== Helper methods =====

    /** Adds stub child items to the operation. Each stub tracks whether it was started. */
    private void addStubItems(TestConcurrencyOperation op, int count) {
        for (int i = 0; i < count; i++) {
            op.addItem("item-" + i, ctx -> null, TypeToken.get(Void.class), SER_DES);
        }
    }

    /**
     * Simulates item completions by directly updating the atomic counters. This bypasses the actual child operation
     * execution to test canComplete() logic in isolation.
     */
    private void simulateItemCompletions(TestConcurrencyOperation op, int successes, int failures) {
        for (int i = 0; i < successes; i++) {
            op.incrementSucceeded();
        }
        for (int i = 0; i < failures; i++) {
            op.incrementFailed();
        }
    }

    /**
     * Concrete test implementation of ConcurrencyOperation. Tracks handleSuccess/handleFailure calls and uses stub
     * child operations.
     */
    static class TestConcurrencyOperation extends ConcurrencyOperation<Void> {

        private boolean successHandled = false;
        private boolean failureHandled = false;
        private final AtomicInteger startedCount = new AtomicInteger(0);

        TestConcurrencyOperation(
                OperationIdentifier operationIdentifier,
                TypeToken<Void> resultTypeToken,
                SerDes resultSerDes,
                DurableContext durableContext,
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
                DurableContext parentContext) {
            // Create a stub ChildContextOperation that tracks execution
            var opId = OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.PARALLEL);
            var childOp =
                    new StubChildContextOperation<>(opId, function, resultType, serDes, parentContext, startedCount);
            return childOp;
        }

        @Override
        protected void handleSuccess() {
            successHandled = true;
        }

        @Override
        protected void handleFailure() {
            failureHandled = true;
        }

        @Override
        protected void start() {
            // no-op for tests
        }

        @Override
        protected void replay(Operation existing) {
            // no-op for tests
        }

        @Override
        public Void get() {
            return null;
        }

        // Expose protected methods for testing
        boolean exposedCanComplete() {
            return canComplete();
        }

        void exposedValidateItemCount() {
            validateItemCount();
        }

        int getStartedCount() {
            return startedCount.get();
        }

        void incrementSucceeded() {
            // Use reflection-free approach: directly call onItemComplete with a mock child
            // that returns successfully. But since we need to test canComplete() in isolation,
            // we access the counters through the parent's getSucceededCount/getFailedCount.
            // For isolated canComplete() testing, we use a different approach.
            try {
                var field = ConcurrencyOperation.class.getDeclaredField("succeededCount");
                field.setAccessible(true);
                ((AtomicInteger) field.get(this)).incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void incrementFailed() {
            try {
                var field = ConcurrencyOperation.class.getDeclaredField("failedCount");
                field.setAccessible(true);
                ((AtomicInteger) field.get(this)).incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        boolean isSuccessHandled() {
            return successHandled;
        }

        boolean isFailureHandled() {
            return failureHandled;
        }
    }

    /** Stub ChildContextOperation that doesn't actually execute but tracks whether execute() was called. */
    static class StubChildContextOperation<T> extends ChildContextOperation<T> {

        private final AtomicInteger startedCount;

        StubChildContextOperation(
                OperationIdentifier operationIdentifier,
                Function<DurableContext, T> function,
                TypeToken<T> resultType,
                SerDes serDes,
                DurableContext durableContext,
                AtomicInteger startedCount) {
            super(operationIdentifier, function, resultType, serDes, durableContext);
            this.startedCount = startedCount;
        }

        @Override
        public void execute() {
            startedCount.incrementAndGet();
        }
    }
}
