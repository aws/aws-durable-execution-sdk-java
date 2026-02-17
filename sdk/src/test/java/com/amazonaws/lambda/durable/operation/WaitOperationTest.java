// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.OperationContext;
import com.amazonaws.lambda.durable.execution.ThreadType;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.WaitDetails;

class WaitOperationTest {
    private static final String OPERATION_ID = "2";
    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
    }

    @Test
    void getThrowsIllegalStateExceptionWhenCalledFromStepContext() {
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("1-step", ThreadType.STEP));

        var operation = new WaitOperation("2", "test-invoke", Duration.ofSeconds(10), executionManager);

        var ex = assertThrows(IllegalDurableOperationException.class, operation::get);
        assertEquals(
                "Nested WAIT operation is not supported on test-invoke from within a Step execution.", ex.getMessage());
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var op = software.amazon.awssdk.services.lambda.model.Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.SUCCEEDED)
                .waitDetails(WaitDetails.builder().build())
                .build();
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new WaitOperation(OPERATION_ID, "test-invoke", Duration.ofSeconds(10), executionManager);
        operation.onCheckpointComplete(op);

        var result = operation.get();
        assertNull(result);
    }

    @Test
    void getSucceededWhenStarted() {
        var op = software.amazon.awssdk.services.lambda.model.Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.SUCCEEDED)
                .build();
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new WaitOperation(OPERATION_ID, "test-invoke", Duration.ofSeconds(10), executionManager);
        operation.onCheckpointComplete(op);

        // we currently don't check the operation status at all, so it's not blocked or failed
        assertNull(operation.get());
    }
}
