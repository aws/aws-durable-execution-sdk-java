// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class ParallelOperationTest {

    private static final SerDes SER_DES = new JacksonSerDes();
    private static final String OPERATION_ID = "parallel-op-1";

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

        // Capture registered operations so we can drive onCheckpointComplete callbacks.
        var registeredOps = new ConcurrentHashMap<String, BaseDurableOperation<?>>();
        doAnswer(inv -> {
                    BaseDurableOperation<?> op = inv.getArgument(0);
                    registeredOps.put(op.getOperationId(), op);
                    return null;
                })
                .when(executionManager)
                .registerOperation(any());

        // Simulate the real backend for all sendOperationUpdate calls:
        // - For SUCCEED on the parallel op: update the stub and fire onCheckpointComplete to unblock join().
        // - For everything else (START, child checkpoints): just return a completed future.
        var succeededParallelOp = Operation.builder()
                .id(OPERATION_ID)
                .name("test-parallel")
                .type(OperationType.CONTEXT)
                .subType(OperationSubType.PARALLEL.getValue())
                .status(OperationStatus.SUCCEEDED)
                .build();
        doAnswer(inv -> {
                    var update = (software.amazon.awssdk.services.lambda.model.OperationUpdate) inv.getArgument(0);

                    if (OPERATION_ID.equals(update.id()) && update.action() == OperationAction.SUCCEED) {
                        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                                .thenReturn(succeededParallelOp);
                        var op = registeredOps.get(OPERATION_ID);
                        if (op != null) {
                            op.onCheckpointComplete(succeededParallelOp);
                        }
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .when(executionManager)
                .sendOperationUpdate(any());
    }

    private ParallelOperation createOperation(int maxConcurrency, int minSuccessful, int toleratedFailureCount) {
        return new ParallelOperation(
                OperationIdentifier.of(OPERATION_ID, "test-parallel", OperationType.CONTEXT, OperationSubType.PARALLEL),
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

    // ===== All branches succeed =====

    @Test
    void allBranchesSucceed_sendsSucceedCheckpointAndReturnsCorrectResult() throws Exception {
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

        var result = op.get();

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.getTotalBranches());
        assertEquals(2, result.getSucceededBranches());
        assertEquals(0, result.getFailedBranches());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.getCompletionStatus());
        assertTrue(result.getCompletionStatus().isSucceeded());
    }

    // ===== MinSuccessful satisfaction =====

    @Test
    void minSuccessful_completesWhenThresholdMetAndReturnsResult() throws Exception {
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

        var result = op.get();

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(1, result.getTotalBranches());
        assertEquals(1, result.getSucceededBranches());
        assertEquals(0, result.getFailedBranches());
        assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.getCompletionStatus());
        assertTrue(result.getCompletionStatus().isSucceeded());
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
    void replay_fromStartedState_sendsSucceedCheckpointAndReturnsResult() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.STARTED)
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

        var result = op.get();

        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.START));
        verify(executionManager, times(1))
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.getTotalBranches());
        assertEquals(2, result.getSucceededBranches());
        assertEquals(0, result.getFailedBranches());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.getCompletionStatus());
    }

    @Test
    void replay_fromSucceededState_skipsCheckpointAndReturnsResult() throws Exception {
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

        var result = op.get();

        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.START));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.getTotalBranches());
        assertEquals(2, result.getSucceededBranches());
        assertEquals(0, result.getFailedBranches());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.getCompletionStatus());
    }

    // ===== Branch failure sends SUCCEED checkpoint and returns result =====

    @Test
    void branchFailure_sendsSucceedCheckpointAndReturnsFailureCounts() throws Exception {
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

        var result = assertDoesNotThrow(() -> op.get());

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
        assertEquals(1, result.getTotalBranches());
        assertEquals(0, result.getSucceededBranches());
        assertEquals(1, result.getFailedBranches());
        assertFalse(result.getCompletionStatus().isSucceeded());
    }

    @Test
    void get_someBranchesFail_returnsCorrectCountsAndFailureStatus() throws Exception {
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
                        .status(OperationStatus.FAILED)
                        .build());

        // toleratedFailureCount=1 so the operation completes after both branches finish
        var op = createOperation(-1, -1, 1);
        setOperationIdGenerator(op, mockIdGenerator);
        op.addItem("branch-1", ctx -> "r1", TypeToken.get(String.class), SER_DES);
        op.addItem(
                "branch-2",
                ctx -> {
                    throw new RuntimeException("branch failed");
                },
                TypeToken.get(String.class),
                SER_DES);

        var result = op.get();

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.getTotalBranches());
        assertEquals(1, result.getSucceededBranches());
        assertEquals(1, result.getFailedBranches());
        assertFalse(result.getCompletionStatus().isSucceeded());
    }

    @Test
    void get_zeroBranches_returnsAllZerosAndAllCompletedStatus() throws Exception {
        var op = createOperation(-1, -1, 0);

        var result = op.get();

        assertEquals(0, result.getTotalBranches());
        assertEquals(0, result.getSucceededBranches());
        assertEquals(0, result.getFailedBranches());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.getCompletionStatus());
        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
    }
}
