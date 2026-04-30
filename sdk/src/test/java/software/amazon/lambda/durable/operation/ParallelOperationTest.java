// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
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
    private static final String CHILD_OP_1 = TestUtils.hashOperationId(OPERATION_ID + "-1");
    private static final String CHILD_OP_2 = TestUtils.hashOperationId(OPERATION_ID + "-2");

    private DurableContextImpl durableContext;
    private ExecutionManager executionManager;
    // Thread-safe backing store for getOperationAndUpdateReplayState.
    // Tests pre-populate this; doAnswer writes here before firing onCheckpointComplete,
    // guaranteeing visibility to any thread that reads after the future unblocks.
    private ConcurrentHashMap<String, Operation> operationStore;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContextImpl.class);
        executionManager = mock(ExecutionManager.class);
        operationStore = new ConcurrentHashMap<>();

        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext(null, ThreadType.CONTEXT));
        // Delegate to operationStore so all reads see the latest write, regardless of thread.
        when(executionManager.getOperationAndUpdateReplayState(anyString()))
                .thenAnswer(inv -> operationStore.get(inv.getArgument(0)));

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
        when(durableContext.createChildContext(anyString(), anyString(), eq(false)))
                .thenReturn(childContext);

        // Capture registered operations so we can drive onCheckpointComplete callbacks.
        var registeredOps = new ConcurrentHashMap<String, BaseDurableOperation>();
        doAnswer(inv -> {
                    BaseDurableOperation op = inv.getArgument(0);
                    registeredOps.put(op.getOperationId(), op);
                    return null;
                })
                .when(executionManager)
                .registerOperation(any());

        // Simulate the real backend for all sendOperationUpdate calls.
        // For SUCCEED on the parallel op: write to operationStore first (establishes happens-before
        // via ConcurrentHashMap's volatile semantics), then fire onCheckpointComplete to unblock join().
        // This ordering guarantees getOperationAndUpdateReplayState() never returns null after unblocking.
        var succeededParallelOp = Operation.builder()
                .id(OPERATION_ID)
                .name("test-parallel")
                .type(OperationType.CONTEXT)
                .subType(OperationSubType.PARALLEL.getValue())
                .status(OperationStatus.SUCCEEDED)
                .build();
        doAnswer(inv -> {
                    var update = (OperationUpdate) inv.getArgument(0);

                    if (OPERATION_ID.equals(update.id()) && update.action() == OperationAction.SUCCEED) {
                        // Write before completing the future — ConcurrentHashMap guarantees visibility.
                        operationStore.put(OPERATION_ID, succeededParallelOp);
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

    private ParallelOperation createOperation(CompletionConfig completionConfig) {
        var op = new ParallelOperation(
                OperationIdentifier.of(OPERATION_ID, "test-parallel", OperationType.CONTEXT, OperationSubType.PARALLEL),
                SER_DES,
                durableContext,
                ParallelConfig.builder().completionConfig(completionConfig).build());

        op.execute();
        return op;
    }

    // ===== Branch creation delegates to ConcurrencyOperation =====

    @Test
    void branchCreation_createsBranchWithParallelBranchSubType() {
        var op = createOperation(CompletionConfig.allSuccessful());

        var childOp = op.enqueueItem(
                "branch-1",
                ctx -> "result",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        assertNotNull(childOp);
        assertEquals(OperationSubType.PARALLEL_BRANCH, childOp.getSubType());
    }

    @Test
    void branchCreation_multipleBranchesAllCreated() {
        var op = createOperation(CompletionConfig.allSuccessful());

        op.enqueueItem(
                "branch-1",
                ctx2 -> "r1",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-2",
                ctx1 -> "r2",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-3", ctx -> "r3", TypeToken.get(String.class), SER_DES, OperationSubType.PARALLEL_BRANCH, false);

        assertEquals(3, op.getBranches().size());
    }

    @Test
    void branchCreation_childOperationHasParentReference() throws Exception {
        var op = createOperation(CompletionConfig.allSuccessful());

        // The child operation should be a ChildContextOperation with this op as parent
        var childOp = op.enqueueItem(
                "branch-1",
                ctx -> "result",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        assertNotNull(childOp);
        // Verify it's a ChildContextOperation (the concrete type returned by createItem)
        assertInstanceOf(ChildContextOperation.class, childOp);
    }

    // ===== All branches succeed =====

    @Test
    void allBranchesSucceed_sendsSucceedCheckpointAndReturnsCorrectResult() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_2))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_2)
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r2\"").build())
                        .build());

        var op = createOperation(CompletionConfig.allSuccessful());
        op.enqueueItem(
                "branch-1",
                ctx1 -> "r1",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-2", ctx -> "r2", TypeToken.get(String.class), SER_DES, OperationSubType.PARALLEL_BRANCH, false);

        var result = op.get();

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.size());
        assertEquals(2, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());
        assertTrue(result.completionStatus().isSucceeded());
    }

    // ===== MinSuccessful satisfaction =====

    @Test
    void minSuccessful_completesWhenThresholdMetAndReturnsResult() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());

        var op = createOperation(CompletionConfig.minSuccessful(1));
        op.enqueueItem(
                "branch-1", ctx -> "r1", TypeToken.get(String.class), SER_DES, OperationSubType.PARALLEL_BRANCH, false);

        var result = op.get();

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(1, result.size());
        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionStatus());
        assertTrue(result.completionStatus().isSucceeded());
    }

    @Test
    void minSuccessful_notExecuteSkippedBranchWhenReplay() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name("test-parallel")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(ContextDetails.builder()
                                .result(
                                        "{\"size\": 2, \"skipped\": 1, \"succeeded\": 1, \"completionStatus\": \"MIN_SUCCESSFUL_REACHED\", \"statuses\":[\"SKIPPED\", \"SUCCEEDED\"]}")
                                .build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_2))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_2)
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r2\"").build())
                        .build());

        var op = createOperation(CompletionConfig.minSuccessful(1));
        op.branch(
                "branch-1",
                TypeToken.get(String.class),
                ctx -> "r1",
                ParallelBranchConfig.builder().serDes(SER_DES).build());
        op.branch(
                "branch-2",
                TypeToken.get(String.class),
                ctx -> "r2",
                ParallelBranchConfig.builder().serDes(SER_DES).build());

        var result = op.get();

        verify(executionManager, never()).sendOperationUpdate(any());
        assertEquals(2, result.size());
        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(1, result.skipped());
        assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionStatus());
        assertTrue(result.completionStatus().isSucceeded());
    }

    // ===== Context hierarchy =====

    @Test
    void contextHierarchy_branchesUseParallelContextAsParent() throws Exception {
        // Verify that branches are created with the parallel operation's context (durableContext)
        // as their parent — not some other context
        var op = createOperation(CompletionConfig.allSuccessful());

        var childOp = op.enqueueItem(
                "branch-1",
                ctx -> "result",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

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
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_2))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_2)
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r2\"").build())
                        .build());

        var op = createOperation(CompletionConfig.allSuccessful());
        op.enqueueItem(
                "branch-1",
                ctx1 -> "r1",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-2", ctx -> "r2", TypeToken.get(String.class), SER_DES, OperationSubType.PARALLEL_BRANCH, false);

        var result = op.get();

        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.START));
        verify(executionManager, times(1))
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.size());
        assertEquals(2, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());
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
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_2))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_2)
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r2\"").build())
                        .build());

        var op = createOperation(CompletionConfig.allSuccessful());
        op.enqueueItem(
                "branch-1",
                ctx1 -> "r1",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-2", ctx -> "r2", TypeToken.get(String.class), SER_DES, OperationSubType.PARALLEL_BRANCH, false);

        var result = op.get();

        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.START));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.size());
        assertEquals(2, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());
    }

    // ===== Branch failure sends SUCCEED checkpoint and returns result =====

    @Test
    void branchFailure_sendsSucceedCheckpointAndReturnsFailureCounts() {
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.FAILED)
                        .build());

        var op = createOperation(CompletionConfig.allSuccessful());
        op.enqueueItem(
                "branch-1",
                ctx -> {
                    throw new RuntimeException("branch failed");
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        var result = assertDoesNotThrow(op::get);

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
        assertEquals(1, result.size());
        assertEquals(0, result.succeeded());
        assertEquals(1, result.failed());
        assertFalse(result.completionStatus().isSucceeded());
    }

    @Test
    void get_someBranchesFail_returnsCorrectCountsAndFailureStatus() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_1))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_1)
                        .name("branch-1")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"r1\"").build())
                        .build());
        when(executionManager.getOperationAndUpdateReplayState(CHILD_OP_2))
                .thenReturn(Operation.builder()
                        .id(CHILD_OP_2)
                        .name("branch-2")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.PARALLEL_BRANCH.getValue())
                        .status(OperationStatus.FAILED)
                        .build());

        // toleratedFailureCount=1 so the operation completes after both branches finish
        var op = createOperation(CompletionConfig.toleratedFailureCount(1));
        op.enqueueItem(
                "branch-1",
                ctx1 -> "r1",
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);
        op.enqueueItem(
                "branch-2",
                ctx -> {
                    throw new RuntimeException("branch failed");
                },
                TypeToken.get(String.class),
                SER_DES,
                OperationSubType.PARALLEL_BRANCH,
                false);

        var result = op.get();

        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        assertEquals(2, result.size());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
        assertTrue(result.completionStatus().isSucceeded());
    }

    @Test
    void get_zeroBranches_returnsAllZerosAndAllCompletedStatus() throws Exception {
        var op = createOperation(CompletionConfig.allSuccessful());

        var result = op.get();

        assertEquals(0, result.size());
        assertEquals(0, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());
        verify(executionManager).sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
    }
}
