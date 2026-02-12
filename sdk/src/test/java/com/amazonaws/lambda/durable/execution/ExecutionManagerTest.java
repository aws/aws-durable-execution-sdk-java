// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.TestUtils;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

class ExecutionManagerTest {

    private ExecutionManager createManager(List<Operation> operations) {
        var client = TestUtils.createMockClient();
        var initialState =
                CheckpointUpdatedExecutionState.builder().operations(operations).build();
        return new ExecutionManager(
                "arn:aws:lambda:us-east-1:123456789012:function:test", "test-token", initialState, client);
    }

    private Operation executionOp() {
        return Operation.builder()
                .id("0")
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
}
