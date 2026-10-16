// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;

/**
 * Unit tests for {@link ExecutionManager#waitForFirstOperationCompletion(List)}, the coordination behind
 * {@code DurableFuture.anyOf}. Covers GitHub issue #707: the caller must be deregistered while it waits so the
 * execution can suspend, and must be registered again before it resumes.
 */
class BaseDurableOperationFirstCompletionTest {

    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final String EXECUTION_OP_ID = "exec-123";
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/exec-name/" + EXECUTION_OP_ID;
    private static final String CALLER_THREAD = "Root";
    private static final String OTHER_THREAD = "other";
    private static final String FIRST_OP_ID = "op-1";
    private static final String SECOND_OP_ID = "op-2";

    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    @BeforeEach
    void setUp() {
        initExecution(List.of());
    }

    @Test
    void rejectsNullOperationList() {
        var thrown = assertThrows(
                IllegalArgumentException.class, () -> executionManager.waitForFirstOperationCompletion(null));

        assertEquals("waitForFirstOperationCompletion requires at least one operation", thrown.getMessage());
    }

    @Test
    void rejectsEmptyOperationList() {
        var thrown = assertThrows(
                IllegalArgumentException.class, () -> executionManager.waitForFirstOperationCompletion(List.of()));

        assertEquals("waitForFirstOperationCompletion requires at least one operation", thrown.getMessage());
    }

    @Test
    void staticWaitRejectsNullOperation() {
        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> BaseDurableOperation.waitForFirstOperationCompletion(
                        Collections.singletonList((BaseDurableOperation) null)));

        assertEquals("waitForFirstOperationCompletion requires non-null operations", thrown.getMessage());
    }

    @Test
    void staticWaitRejectsOperationsFromDifferentExecutionManagers() {
        var local = operation(FIRST_OP_ID);
        initExecution(List.of());
        var foreign = operation(SECOND_OP_ID);

        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> BaseDurableOperation.waitForFirstOperationCompletion(List.of(local, foreign)));

        assertEquals(
                "waitForFirstOperationCompletion requires operations from the same ExecutionManager",
                thrown.getMessage());
    }

    @Test
    void rejectsDurableWaitFromStepThreadWithOperationDetails() {
        executionManager.setCurrentThreadContext(new ThreadContext(CALLER_THREAD, ThreadType.STEP));

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> executionManager.validateCurrentThreadCanWaitForDurableOperation(OperationType.STEP, "name"));

        assertEquals(
                "Nested STEP operation is not supported on name from within a Step execution.", thrown.getMessage());
    }

    @Test
    void rejectsFirstCompletionWaitFromStepThread() {
        executionManager.setCurrentThreadContext(new ThreadContext(CALLER_THREAD, ThreadType.STEP));
        var first = operation(FIRST_OP_ID);

        var thrown = assertThrows(
                IllegalStateException.class, () -> executionManager.waitForFirstOperationCompletion(List.of(first)));

        assertEquals("Nested durable operation is not supported from within a Step execution.", thrown.getMessage());
    }

    @Test
    void returnsAlreadySettledOperationWithoutParkingTheCaller() {
        var pending = operation(FIRST_OP_ID);
        var settled = operation(SECOND_OP_ID);
        settled.settle();

        var operation = executionManager.waitForFirstOperationCompletion(List.of(pending, settled));

        assertEquals(settled, operation);
        assertFalse(executionManager.isExecutionCompletedExceptionally(), "caller must not have triggered suspension");
    }

    @Test
    void suspendsWhenNoOperationCanSettle() {
        initExecution(List.of(pendingStepOperation(FIRST_OP_ID), pendingStepOperation(SECOND_OP_ID)));
        var first = operation(FIRST_OP_ID);
        var second = operation(SECOND_OP_ID);

        assertThrows(
                SuspendExecutionException.class,
                () -> executionManager.waitForFirstOperationCompletion(List.of(first, second)));
    }

    @Test
    void registersCallerAgainWhenAnOperationSettlesLater() {
        executionManager.registerActiveThread(OTHER_THREAD);
        var pending = operation(FIRST_OP_ID);
        var settling = operation(SECOND_OP_ID);
        CompletableFuture.runAsync(settling::settle, CompletableFuture.delayedExecutor(50, MILLISECONDS));

        var operation = awaitFirstSettled(List.of(pending, settling));

        assertEquals(settling, operation);
        assertFalse(executionManager.isExecutionCompletedExceptionally());
        // The caller is active again: releasing the other thread leaves it as the last active thread.
        assertDoesNotThrow(() -> executionManager.deregisterActiveThread(OTHER_THREAD));
        assertThrows(SuspendExecutionException.class, () -> executionManager.deregisterActiveThread(CALLER_THREAD));
    }

    @Test
    void waitsForCallerReactivationBeforeReturningFirstCompletion() throws Exception {
        var manager = initBlockingReactivationExecution();
        executionManager.registerActiveThread(OTHER_THREAD);
        var pending = operation(FIRST_OP_ID);
        var settling = operation(SECOND_OP_ID);
        manager.blockCallerRegistration();

        var waiter = CompletableFuture.supplyAsync(() -> {
            adoptCallerThreadContext();
            return executionManager.waitForFirstOperationCompletion(List.of(pending, settling));
        });

        assertTrue(manager.awaitCallerDeregistration());
        var completion = CompletableFuture.runAsync(settling::settle);
        assertTrue(manager.awaitCallerRegistration());
        assertThrows(TimeoutException.class, () -> waiter.get(100, MILLISECONDS));

        manager.allowCallerRegistration();

        assertEquals(settling, waiter.get(5_000, MILLISECONDS));
        completion.get(5_000, MILLISECONDS);
    }

    @Test
    void rethrowsSuspensionInsteadOfReturningACancelledOperation() {
        var cancelled = operation(FIRST_OP_ID);
        var pending = operation(SECOND_OP_ID);
        cancelled.getCompletionFuture().completeExceptionally(new SuspendExecutionException());

        assertThrows(
                SuspendExecutionException.class,
                () -> executionManager.waitForFirstOperationCompletion(List.of(cancelled, pending)));
    }

    @Test
    void wakesParkedCallerWhenAnotherThreadSuspendsTheExecution() {
        executionManager.registerActiveThread(OTHER_THREAD);
        var first = operation(FIRST_OP_ID);
        var second = operation(SECOND_OP_ID);
        CompletableFuture.runAsync(
                () -> {
                    try {
                        executionManager.deregisterActiveThread(OTHER_THREAD);
                    } catch (SuspendExecutionException expected) {
                        // The last thread to deregister signals suspension from inside the thread lock.
                    }
                },
                CompletableFuture.delayedExecutor(50, MILLISECONDS));

        // Cancelling the operations from a thread that holds the execution's thread lock must not block on the parked
        // caller, and the caller must surface the suspension rather than a settled index.
        assertTimeoutPreemptively(DEADLINE, () -> {
            adoptCallerThreadContext();
            assertThrows(
                    SuspendExecutionException.class,
                    () -> executionManager.waitForFirstOperationCompletion(List.of(first, second)));
        });
    }

    /**
     * Runs the wait under a deadline so a coordination deadlock fails instead of hanging. The deadline runs the wait on
     * its own thread, which needs the caller's thread context; the registered thread id is unaffected.
     */
    private DurableFuture<?> awaitFirstSettled(List<? extends BaseDurableOperation> operations) {
        return assertTimeoutPreemptively(DEADLINE, () -> {
            adoptCallerThreadContext();
            return executionManager.waitForFirstOperationCompletion(operations);
        });
    }

    private void adoptCallerThreadContext() {
        executionManager.setCurrentThreadContext(new ThreadContext(CALLER_THREAD, ThreadType.CONTEXT));
    }

    private void initExecution(List<Operation> initialOperations) {
        var operations = new ArrayList<Operation>();
        operations.add(Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build());
        operations.addAll(initialOperations);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(new NoopDurableExecutionClient())
                .build();
        executionManager = new ExecutionManager(
                new DurableExecutionInput(
                        EXECUTION_ARN,
                        "test-token",
                        CheckpointUpdatedExecutionState.builder()
                                .operations(operations)
                                .build()),
                config,
                null);
        executionManager.setCurrentThreadContext(new ThreadContext(CALLER_THREAD, ThreadType.CONTEXT));
        executionManager.registerActiveThread(CALLER_THREAD);
        durableContext = DurableContextImpl.createRootContext(executionManager, config, null);
    }

    private Operation pendingStepOperation(String operationId) {
        return Operation.builder()
                .id(operationId)
                .type(OperationType.STEP)
                .status(OperationStatus.PENDING)
                .build();
    }

    private BlockingReactivationExecutionManager initBlockingReactivationExecution() {
        var operations = List.of(Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build());
        var config = DurableConfig.builder()
                .withDurableExecutionClient(new NoopDurableExecutionClient())
                .build();
        var manager = new BlockingReactivationExecutionManager(
                new DurableExecutionInput(
                        EXECUTION_ARN,
                        "test-token",
                        CheckpointUpdatedExecutionState.builder()
                                .operations(operations)
                                .build()),
                config);
        executionManager = manager;
        executionManager.setCurrentThreadContext(new ThreadContext(CALLER_THREAD, ThreadType.CONTEXT));
        executionManager.registerActiveThread(CALLER_THREAD);
        durableContext = DurableContextImpl.createRootContext(executionManager, config, null);
        return manager;
    }

    private TestOperation operation(String operationId) {
        return new TestOperation(operationId, durableContext);
    }

    /** Minimal operation that settles on demand, so the waiting behaviour can be driven directly. */
    private static final class TestOperation extends BaseDurableOperation implements DurableFuture<Void> {
        private TestOperation(String operationId, DurableContextImpl durableContext) {
            super(OperationIdentifier.of(operationId, operationId, OperationSubType.STEP), durableContext, null);
        }

        @Override
        protected void start() {}

        @Override
        protected void replay(Operation existing) {}

        @Override
        public Void get() {
            return null;
        }

        private void settle() {
            markAlreadyCompleted();
        }
    }

    private static final class BlockingReactivationExecutionManager extends ExecutionManager {
        private final CountDownLatch callerDeregistered = new CountDownLatch(1);
        private final CountDownLatch callerRegistrationStarted = new CountDownLatch(1);
        private final CountDownLatch allowCallerRegistration = new CountDownLatch(1);
        private volatile boolean blockCallerRegistration;

        private BlockingReactivationExecutionManager(DurableExecutionInput input, DurableConfig config) {
            super(input, config, null);
        }

        @Override
        public void registerActiveThread(String threadId) {
            if (blockCallerRegistration && CALLER_THREAD.equals(threadId)) {
                callerRegistrationStarted.countDown();
                await(allowCallerRegistration);
            }
            super.registerActiveThread(threadId);
        }

        @Override
        public void deregisterActiveThread(String threadId) {
            super.deregisterActiveThread(threadId);
            if (CALLER_THREAD.equals(threadId)) {
                callerDeregistered.countDown();
            }
        }

        private void blockCallerRegistration() {
            blockCallerRegistration = true;
        }

        private boolean awaitCallerDeregistration() throws InterruptedException {
            return callerDeregistered.await(5_000, MILLISECONDS);
        }

        private boolean awaitCallerRegistration() throws InterruptedException {
            return callerRegistrationStarted.await(5_000, MILLISECONDS);
        }

        private void allowCallerRegistration() {
            allowCallerRegistration.countDown();
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(5_000, MILLISECONDS)) {
                    throw new AssertionError("timed out waiting for caller registration to unblock");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for caller registration to unblock", e);
            }
        }
    }

    private static final class NoopDurableExecutionClient implements DurableExecutionClient {
        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            throw new UnsupportedOperationException("checkpoint should not be called by this test");
        }

        @Override
        public GetDurableExecutionStateResponse getExecutionState(String arn, String checkpointToken, String marker) {
            throw new UnsupportedOperationException("getExecutionState should not be called by this test");
        }
    }
}
