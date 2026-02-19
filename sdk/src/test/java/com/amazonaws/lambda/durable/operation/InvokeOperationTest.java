// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.lambda.durable.InvokeConfig;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import com.amazonaws.lambda.durable.exception.InvokeException;
import com.amazonaws.lambda.durable.exception.InvokeFailedException;
import com.amazonaws.lambda.durable.exception.InvokeStoppedException;
import com.amazonaws.lambda.durable.exception.InvokeTimedOutException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.OperationContext;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

class InvokeOperationTest {
    private static final String OPERATION_ID = "2";

    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("root", ThreadType.CONTEXT));
    }

    @Test
    void getThrowsIllegalStateExceptionWhenCalledFromStepContext() {
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("1-step", ThreadType.STEP));
        var operation = new InvokeOperation<>(
                OPERATION_ID,
                "test-invoke",
                "function-name",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                executionManager);

        var ex = assertThrows(IllegalDurableOperationException.class, operation::get);
        assertTrue(ex.getMessage().contains("Nested CHAINED_INVOKE operation is not supported"));
        assertTrue(ex.getMessage().contains("test-invoke"));
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var op = software.amazon.awssdk.services.lambda.model.Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.SUCCEEDED)
                .chainedInvokeDetails(software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                        .result("\"cached-result\"")
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new InvokeOperation<>(
                OPERATION_ID,
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                executionManager);
        operation.onCheckpointComplete(op);

        var result = operation.get();
        assertEquals("cached-result", result);
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationFailed() {
        var op = software.amazon.awssdk.services.lambda.model.Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("errorType")
                                .errorMessage("errorMessage")
                                .errorData("errorData")
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new InvokeOperation<>(
                OPERATION_ID,
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                executionManager);
        operation.onCheckpointComplete(op);

        InvokeFailedException ex = assertThrows(InvokeFailedException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorObject().errorData());
        assertEquals("errorType", ex.getErrorObject().errorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeTimedOutExceptionWhenInvocationTimedOut() {
        var op = software.amazon.awssdk.services.lambda.model.Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.TIMED_OUT)
                .chainedInvokeDetails(software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("errorType")
                                .errorMessage("errorMessage")
                                .errorData("errorData")
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new InvokeOperation<>(
                OPERATION_ID,
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                executionManager);
        operation.onCheckpointComplete(op);

        InvokeTimedOutException ex = assertThrows(InvokeTimedOutException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorObject().errorData());
        assertEquals("errorType", ex.getErrorObject().errorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeStoppedExceptionWhenInvocationTimedOut() {
        var op = software.amazon.awssdk.services.lambda.model.Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.STOPPED)
                .chainedInvokeDetails(software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("errorType")
                                .errorMessage("errorMessage")
                                .errorData("errorData")
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new InvokeOperation<>(
                OPERATION_ID,
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                executionManager);
        operation.onCheckpointComplete(op);

        InvokeStoppedException ex = assertThrows(InvokeStoppedException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorObject().errorData());
        assertEquals("errorType", ex.getErrorObject().errorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationEndedUnexpectedly() {
        var op = software.amazon.awssdk.services.lambda.model.Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.CANCELLED)
                .chainedInvokeDetails(software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("errorType")
                                .errorMessage("errorMessage")
                                .errorData("errorData")
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new InvokeOperation<>(
                OPERATION_ID,
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                executionManager);
        operation.onCheckpointComplete(op);

        assertThrows(InvokeException.class, () -> operation.get());
    }
}
