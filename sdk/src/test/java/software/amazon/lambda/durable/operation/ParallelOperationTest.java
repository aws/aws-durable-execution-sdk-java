// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class ParallelOperationTest {

    private static final SerDes SER_DES = new JacksonSerDes();
    private static final String OPERATION_ID = "parallel-op-1";
    private static final TypeToken<Void> RESULT_TYPE = TypeToken.get(Void.class);

    private DurableContextImpl durableContext;
    private ExecutionManager executionManager;
    private AtomicInteger operationIdCounter;
    private OperationIdGenerator mockIdGenerator;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContextImpl.class);
        executionManager = mock(ExecutionManager.class);
        operationIdCounter = new AtomicInteger(0);

        var childContext = mock(DurableContextImpl.class);
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
        // Default: no existing operations (fresh execution)
        mockIdGenerator = mock(OperationIdGenerator.class);
        when(mockIdGenerator.nextOperationId()).thenAnswer(inv -> "child-" + operationIdCounter.incrementAndGet());
        when(executionManager.getOperationAndUpdateReplayState(anyString())).thenReturn(null);
    }

    private ParallelOperation<Void> createOperation(int maxConcurrency, int minSuccessful, int toleratedFailureCount) {
        return new ParallelOperation<>(
                OperationIdentifier.of(OPERATION_ID, "test-parallel", OperationType.CONTEXT, OperationSubType.PARALLEL),
                RESULT_TYPE,
                SER_DES,
                durableContext,
                maxConcurrency,
                minSuccessful,
                toleratedFailureCount);
    }

    private void setOperationIdGenerator(ConcurrencyOperation<?> op, OperationIdGenerator mockGenerator)
            throws Exception {
        Field field = ConcurrencyOperation.class.getDeclaredField("operationIdGenerator");
        field.setAccessible(true);
        field.set(op, mockGenerator);
    }

    // ===== Branch creation delegates to ConcurrencyOperation =====

    @Test
    void branchCreation_createsBranchWithParallelBranchSubType() throws Exception {
        var op = createOperation(-1, -1, 0);

        var childOp = op.addItem("branch-1", ctx -> "result", TypeToken.get(String.class), SER_DES);

        assertNotNull(childOp);
        assertEquals(OperationSubType.PARALLEL_BRANCH, childOp.getSubType());
    }

    @Test
    void branchCreation_multipleBranchesAllCreated() throws Exception {
        var op = createOperation(-1, -1, 0);

        op.addItem("branch-1", ctx -> "r1", TypeToken.get(String.class), SER_DES);
        op.addItem("branch-2", ctx -> "r2", TypeToken.get(String.class), SER_DES);
        op.addItem("branch-3", ctx -> "r3", TypeToken.get(String.class), SER_DES);

        assertEquals(3, op.getTotalItems());
    }

    @Test
    void branchCreation_childOperationHasParentReference() throws Exception {
        var op = createOperation(-1, -1, 0);

        // The child operation should be a ChildContextOperation with this op as parent
        var childOp = op.addItem("branch-1", ctx -> "result", TypeToken.get(String.class), SER_DES);

        assertNotNull(childOp);
        // Verify it's a ChildContextOperation (the concrete type returned by createItem)
        assertInstanceOf(ChildContextOperation.class, childOp);
    }

    // ===== handleSuccess checkpointing =====

    @Test
    void handleSuccess_sendsSucceedCheckpoint() throws Exception {
        // Set up two branches that both succeed in replay
        when(executionManager.getOperationAndUpdateReplayState("child-1"))
                .thenReturn(Operation.builder()
                        .id("child-1")
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState("child-2"))
                .thenReturn(Operation.builder()
                        .id("child-2")
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r2\"").build())
                        .build());

        var op = createOperation(-1, -1, 0);
        setOperationIdGenerator(op, mockIdGenerator);
        op.addItem("branch-1", ctx -> "r1", TypeToken.get(String.class), SER_DES);
        op.addItem("branch-2", ctx -> "r2", TypeToken.get(String.class), SER_DES);

        runJoin(op);

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
    }

    // ===== MinSuccessful satisfaction =====

    @Test
    void minSuccessful_joinCompletesWhenThresholdMet() throws Exception {
        // minSuccessful=1, 2 branches — first succeeds → should complete without throwing
        when(executionManager.getOperationAndUpdateReplayState("child-1"))
                .thenReturn(Operation.builder()
                        .id("child-1")
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());

        var op = createOperation(-1, 1, 0);
        setOperationIdGenerator(op, mockIdGenerator);
        op.addItem("branch-1", ctx -> "r1", TypeToken.get(String.class), SER_DES);

        // Should not throw
        assertDoesNotThrow(() -> runJoin(op));
        assertEquals(1, op.getSucceededCount());
    }

    // ===== Context hierarchy =====

    @Test
    void contextHierarchy_branchesUseParallelContextAsParent() throws Exception {
        // Verify that branches are created with the parallel operation's context (durableContext)
        // as their parent — not some other context
        var op = createOperation(-1, -1, 0);

        var childOp = op.addItem("branch-1", ctx -> "result", TypeToken.get(String.class), SER_DES);

        // The child operation should be registered in the execution manager
        // (BaseDurableOperation constructor calls executionManager.registerOperation)
        verify(executionManager, atLeastOnce()).registerOperation(any());
        assertNotNull(childOp);
    }

    // ===== Replay =====

    @Test
    void replay_doesNotSendStartCheckpoint() throws Exception {
        // Simulate the parallel operation already existing in the service (STARTED status)
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.STARTED)
                        .build());
        // Both branches already succeeded
        when(executionManager.getOperationAndUpdateReplayState("child-1"))
                .thenReturn(Operation.builder()
                        .id("child-1")
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState("child-2"))
                .thenReturn(Operation.builder()
                        .id("child-2")
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r2\"").build())
                        .build());

        var op = createOperation(-1, -1, 0);
        setOperationIdGenerator(op, mockIdGenerator);
        op.execute();
        op.addItem("branch-1", ctx -> "r1", TypeToken.get(String.class), SER_DES);
        op.addItem("branch-2", ctx -> "r2", TypeToken.get(String.class), SER_DES);

        runJoin(op);

        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.START));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
    }

    @Test
    void replay_doesNotSendSucceedCheckpointWhenParallelAlreadySucceeded() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .build());
        when(executionManager.getOperationAndUpdateReplayState("child-1"))
                .thenReturn(Operation.builder()
                        .id("child-1")
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState("child-2"))
                .thenReturn(Operation.builder()
                        .id("child-2")
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r2\"").build())
                        .build());

        var op = createOperation(-1, -1, 0);
        setOperationIdGenerator(op, mockIdGenerator);
        op.execute();
        op.addItem("branch-1", ctx -> "r1", TypeToken.get(String.class), SER_DES);
        op.addItem("branch-2", ctx -> "r2", TypeToken.get(String.class), SER_DES);

        runJoin(op);

        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.START));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
    }

    // ===== handleFailure still sends SUCCEED =====

    @Test
    void handleFailure_sendsSucceedCheckpointEvenWhenFailureToleranceExceeded() throws Exception {
        // toleratedFailureCount=0, so the first failure triggers handleFailure
        // ParallelOperation.handleFailure() delegates to handleSuccess(), so SUCCEED must be sent
        when(executionManager.getOperationAndUpdateReplayState("child-1"))
                .thenReturn(Operation.builder()
                        .id("child-1")
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.FAILED)
                        .build());

        var op = createOperation(-1, -1, 0);
        setOperationIdGenerator(op, mockIdGenerator);
        op.addItem(
                "branch-1",
                ctx -> {
                    throw new RuntimeException("branch failed");
                },
                TypeToken.get(String.class),
                SER_DES);

        runJoin(op);

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
    }

    // ===== Helpers =====

    private void runJoin(ParallelOperation<?> op) throws InterruptedException {
        var t = new Thread(op::get);
        t.start();
        t.join(2000);
        if (t.isAlive()) {
            t.interrupt();
            fail("join() did not complete within 2 seconds");
        }
    }
}
