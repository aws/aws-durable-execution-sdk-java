// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.InvokeConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.InvokeException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class InvokeOperationTest {
    private static final String OPERATION_ID = "2";

    private ExecutionManager executionManager;
    private DurableContext durableContext;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        durableContext = mock(DurableContext.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("root", ThreadType.CONTEXT));
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.SUCCEEDED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
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
                durableContext);
        operation.onCheckpointComplete(op);

        var result = operation.get();
        assertEquals("cached-result", result);
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationFailed() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
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
                durableContext);
        operation.onCheckpointComplete(op);

        InvokeFailedException ex = assertThrows(InvokeFailedException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorObject().errorData());
        assertEquals("errorType", ex.getErrorObject().errorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeTimedOutExceptionWhenInvocationTimedOut() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.TIMED_OUT)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
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
                durableContext);
        operation.onCheckpointComplete(op);

        InvokeTimedOutException ex = assertThrows(InvokeTimedOutException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorObject().errorData());
        assertEquals("errorType", ex.getErrorObject().errorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeStoppedExceptionWhenInvocationTimedOut() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.STOPPED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
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
                durableContext);
        operation.onCheckpointComplete(op);

        InvokeStoppedException ex = assertThrows(InvokeStoppedException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorObject().errorData());
        assertEquals("errorType", ex.getErrorObject().errorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationEndedUnexpectedly() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name("test-invoke")
                .status(OperationStatus.CANCELLED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
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
                durableContext);
        operation.onCheckpointComplete(op);

        assertThrows(InvokeException.class, () -> operation.get());
    }
}
