// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

class ExecutionManagerTest {
    private static final String EXECUTION_OP_ID = "01234567-0123-0123-0123-012345678901";
    private static final String EXECUTION_NAME = "exec-name";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;

    private DurableExecutionClient client;

    private ExecutionManager createManager(List<Operation> operations) {
        client = TestUtils.createMockClient();
        var initialState =
                CheckpointUpdatedExecutionState.builder().operations(operations).build();
        return new ExecutionManager(
                new DurableExecutionInput(EXECUTION_ARN, "test-token", initialState),
                DurableConfig.builder().withDurableExecutionClient(client).build(),
                null);
    }

    private Operation executionOp() {
        return Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
    }

    private Operation stepOp(String id, OperationStatus status) {
        return Operation.builder()
                .id(id)
                .type(OperationType.STEP)
                .status(status)
                .build();
    }

    @Test
    void startsInReplayModeWhenOperationsExist() {
        var manager = createManager(List.of(executionOp(), stepOp("1", OperationStatus.SUCCEEDED)));

        assertTrue(manager.isReplaying());
    }

    @Test
    void startsInExecutionModeWhenOnlyExecutionOp() {
        var manager = createManager(List.of(executionOp()));

        assertFalse(manager.isReplaying());
    }

    @Test
    void staysInReplayModeForTerminalOperation() {
        var manager = createManager(List.of(executionOp(), stepOp("1", OperationStatus.SUCCEEDED)));

        var op = manager.getOperationAndUpdateReplayState("1");

        assertNotNull(op);
        assertTrue(manager.isReplaying());
    }

    @Test
    void transitionsToExecutionModeForNonTerminalOperation() {
        var manager = createManager(List.of(executionOp(), stepOp("1", OperationStatus.STARTED)));

        assertTrue(manager.isReplaying());

        var op = manager.getOperationAndUpdateReplayState("1");

        assertNotNull(op);
        assertFalse(manager.isReplaying());
    }

    @Test
    void transitionsToExecutionModeForMissingOperation() {
        var manager = createManager(List.of(executionOp(), stepOp("1", OperationStatus.SUCCEEDED)));

        assertTrue(manager.isReplaying());

        var op = manager.getOperationAndUpdateReplayState("2");

        assertNull(op);
        assertFalse(manager.isReplaying());
    }

    @Test
    void transitionsToExecutionModeForPendingOperation() {
        var manager = createManager(List.of(executionOp(), stepOp("1", OperationStatus.PENDING)));

        var op = manager.getOperationAndUpdateReplayState("1");

        assertNotNull(op);
        assertFalse(manager.isReplaying());
    }

    @Test
    void staysInReplayModeForFailedOperation() {
        var manager = createManager(List.of(executionOp(), stepOp("1", OperationStatus.FAILED)));

        var op = manager.getOperationAndUpdateReplayState("1");

        assertNotNull(op);
        assertTrue(manager.isReplaying());
    }

    @Test
    void emptyInitialState() {
        client = mock(DurableExecutionClient.class);
        when(client.getExecutionState(any(), any(), any()))
                .thenReturn(GetDurableExecutionStateResponse.builder()
                        .operations(List.of(executionOp()))
                        .nextMarker(null)
                        .build());
        var initialState = CheckpointUpdatedExecutionState.builder()
                .operations(List.of())
                .nextMarker("marker")
                .build();
        var executionManager = new ExecutionManager(
                new DurableExecutionInput(EXECUTION_ARN, "test-token", initialState),
                DurableConfig.builder().withDurableExecutionClient(client).build(),
                null);

        assertNotNull(executionManager.getExecutionOperation());
        assertEquals(EXECUTION_OP_ID, executionManager.getExecutionOperation().id());
    }

    // ─── UpdatedOperationIds tests ───────────────────────────────────────

    @Test
    void isOperationUpdatedSinceLastInvocation_returnsFalse_whenEmptyList() {
        // Default 3-arg constructor uses empty list
        var manager = createManager(List.of(executionOp(), stepOp("1", OperationStatus.SUCCEEDED)));

        assertFalse(manager.isOperationUpdatedSinceLastInvocation("1"));
    }

    @Test
    void isOperationUpdatedSinceLastInvocation_returnsTrue_whenOperationIsInList() {
        client = TestUtils.createMockClient();
        var initialState = CheckpointUpdatedExecutionState.builder()
                .operations(List.of(executionOp(), stepOp("1", OperationStatus.SUCCEEDED)))
                .build();
        var input = new DurableExecutionInput(EXECUTION_ARN, "test-token", initialState, List.of("1"));
        var manager = new ExecutionManager(
                input,
                DurableConfig.builder().withDurableExecutionClient(client).build(),
                null);

        assertTrue(manager.isOperationUpdatedSinceLastInvocation("1"));
    }

    @Test
    void isOperationUpdatedSinceLastInvocation_returnsFalse_whenOperationNotInList() {
        client = TestUtils.createMockClient();
        var initialState = CheckpointUpdatedExecutionState.builder()
                .operations(List.of(executionOp(), stepOp("1", OperationStatus.SUCCEEDED)))
                .build();
        var input = new DurableExecutionInput(EXECUTION_ARN, "test-token", initialState, List.of("2"));
        var manager = new ExecutionManager(
                input,
                DurableConfig.builder().withDurableExecutionClient(client).build(),
                null);

        assertFalse(manager.isOperationUpdatedSinceLastInvocation("1"));
    }

    @Test
    void isOperationUpdatedSinceLastInvocation_handlesMultipleIds() {
        client = TestUtils.createMockClient();
        var initialState = CheckpointUpdatedExecutionState.builder()
                .operations(List.of(executionOp(), stepOp("1", OperationStatus.SUCCEEDED)))
                .build();
        var input = new DurableExecutionInput(EXECUTION_ARN, "test-token", initialState, List.of("1", "2", "3"));
        var manager = new ExecutionManager(
                input,
                DurableConfig.builder().withDurableExecutionClient(client).build(),
                null);

        assertTrue(manager.isOperationUpdatedSinceLastInvocation("1"));
        assertTrue(manager.isOperationUpdatedSinceLastInvocation("2"));
        assertTrue(manager.isOperationUpdatedSinceLastInvocation("3"));
        assertFalse(manager.isOperationUpdatedSinceLastInvocation("4"));
    }

    @Test
    void checkpointStateIsNotPublishedBeforeOperationCompletion() throws Exception {
        var manager = createManager(List.of(executionOp(), stepOp("step", OperationStatus.PENDING)));
        var durableContext = mock(DurableContextImpl.class);
        when(durableContext.getExecutionManager()).thenReturn(manager);

        class TestOperation extends BaseDurableOperation {
            TestOperation() {
                super(OperationIdentifier.of("step", "step", OperationSubType.STEP), durableContext, null);
            }

            @Override
            protected void start() {}

            @Override
            protected void replay(Operation existing) {}

            CompletableFuture<BaseDurableOperation> completionLock() {
                return completionFuture;
            }
        }

        var operation = new TestOperation();
        var checkpointStarted = new CountDownLatch(1);
        CompletableFuture<Void> checkpoint;
        synchronized (operation.completionLock()) {
            checkpoint = CompletableFuture.runAsync(() -> {
                checkpointStarted.countDown();
                manager.onCheckpointComplete(List.of(stepOp("step", OperationStatus.SUCCEEDED)));
            });
            assertTrue(checkpointStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> checkpoint.get(500, TimeUnit.MILLISECONDS));
            assertEquals(
                    OperationStatus.PENDING,
                    manager.getOperationAndUpdateReplayState("step").status());
        }

        checkpoint.get(5, TimeUnit.SECONDS);
        assertTrue(operation.getCompletionFuture().isDone());
        assertEquals(
                OperationStatus.SUCCEEDED,
                manager.getOperationAndUpdateReplayState("step").status());
    }
}
