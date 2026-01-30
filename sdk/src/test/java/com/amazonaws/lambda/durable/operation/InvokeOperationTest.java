// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.InvokeException;
import com.amazonaws.lambda.durable.exception.InvokeFailedException;
import com.amazonaws.lambda.durable.exception.InvokeStoppedException;
import com.amazonaws.lambda.durable.exception.InvokeTimedOutException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.OperationContext;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

class InvokeOperationTest {

    @Test
    void getThrowsIllegalStateExceptionWhenCalledFromStepContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("1-step", ThreadType.STEP));

        var operation = new InvokeOperation<>(
                "1",
                "test-invoke",
                "function-name",
                "{}",
                TypeToken.get(String.class),
                null,
                executionManager,
                new JacksonSerDes());

        var ex = assertThrows(IllegalStateException.class, operation::get);
        assertTrue(ex.getMessage().contains("Nested invoke calling is not supported"));
        assertTrue(ex.getMessage().contains("test-invoke"));
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperation("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-invoke")
                        .status(OperationStatus.SUCCEEDED)
                        .chainedInvokeDetails(
                                software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                                        .result("\"cached-result\"")
                                        .build())
                        .build());

        var operation = new InvokeOperation<>(
                "1",
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                null,
                executionManager,
                new JacksonSerDes());

        var result = operation.get();
        assertEquals("cached-result", result);
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationFailed() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperation("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-invoke")
                        .status(OperationStatus.FAILED)
                        .chainedInvokeDetails(
                                software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                                        .error(ErrorObject.builder()
                                                .errorType("errorType")
                                                .errorMessage("errorMessage")
                                                .errorData("errorData")
                                                .build())
                                        .build())
                        .build());

        var operation = new InvokeOperation<>(
                "1",
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                null,
                executionManager,
                new JacksonSerDes());

        InvokeFailedException ex = assertThrows(InvokeFailedException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorData());
        assertEquals("errorType", ex.getErrorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeTimedOutExceptionWhenInvocationTimedOut() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperation("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-invoke")
                        .status(OperationStatus.TIMED_OUT)
                        .chainedInvokeDetails(
                                software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                                        .error(ErrorObject.builder()
                                                .errorType("errorType")
                                                .errorMessage("errorMessage")
                                                .errorData("errorData")
                                                .build())
                                        .build())
                        .build());

        var operation = new InvokeOperation<>(
                "1",
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                null,
                executionManager,
                new JacksonSerDes());

        InvokeTimedOutException ex = assertThrows(InvokeTimedOutException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorData());
        assertEquals("errorType", ex.getErrorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeStoppedExceptionWhenInvocationTimedOut() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperation("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-invoke")
                        .status(OperationStatus.STOPPED)
                        .chainedInvokeDetails(
                                software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                                        .error(ErrorObject.builder()
                                                .errorType("errorType")
                                                .errorMessage("errorMessage")
                                                .errorData("errorData")
                                                .build())
                                        .build())
                        .build());

        var operation = new InvokeOperation<>(
                "1",
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                null,
                executionManager,
                new JacksonSerDes());

        InvokeStoppedException ex = assertThrows(InvokeStoppedException.class, () -> operation.get());
        assertEquals("errorData", ex.getErrorData());
        assertEquals("errorType", ex.getErrorType());
        assertEquals("errorMessage", ex.getMessage());
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationEndedUnexpectedly() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister(); // Advance to phase 1 to skip blocking
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperation("1"))
                .thenReturn(software.amazon.awssdk.services.lambda.model.Operation.builder()
                        .id("1")
                        .name("test-invoke")
                        .status(OperationStatus.CANCELLED)
                        .chainedInvokeDetails(
                                software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails.builder()
                                        .error(ErrorObject.builder()
                                                .errorType("errorType")
                                                .errorMessage("errorMessage")
                                                .errorData("errorData")
                                                .build())
                                        .build())
                        .build());

        var operation = new InvokeOperation<>(
                "1",
                "test-invoke",
                "test-function",
                "{}",
                TypeToken.get(String.class),
                null,
                executionManager,
                new JacksonSerDes());

        assertThrows(InvokeException.class, () -> operation.get());
    }
}
