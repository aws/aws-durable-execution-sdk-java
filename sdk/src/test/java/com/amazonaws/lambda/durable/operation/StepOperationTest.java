// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.StepFailedException;
import com.amazonaws.lambda.durable.exception.StepInterruptedException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.OperationContext;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.logging.DurableLogger;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import java.util.List;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.StepDetails;

class StepOperationTest {

    private ExecutionManager createMockExecutionManager() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister();
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        return executionManager;
    }

    private void mockFailedOperation(
            ExecutionManager executionManager,
            String errorType,
            String errorMessage,
            String errorData,
            List<String> stackTrace) {
        var operation = Operation.builder()
                .id("1")
                .name("test-step")
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType(errorType)
                                .errorMessage(errorMessage)
                                .errorData(errorData)
                                .stackTrace(stackTrace)
                                .build())
                        .build())
                .build();

        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(operation);
        when(executionManager.getOperation("1")).thenReturn(operation);
    }

    @Test
    void getThrowsIllegalStateExceptionWhenCalledFromStepContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("1-step", ThreadType.STEP));

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        var ex = assertThrows(IllegalStateException.class, operation::get);
        assertTrue(ex.getMessage().contains("Nested step calling is not supported"));
        assertTrue(ex.getMessage().contains("test-step"));
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var executionManager = mock(ExecutionManager.class);
        var phaser = new Phaser(1);
        phaser.arriveAndDeregister();
        when(executionManager.startPhaser(any())).thenReturn(phaser);
        when(executionManager.getCurrentContext()).thenReturn(new OperationContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperation("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-step")
                        .status(OperationStatus.SUCCEEDED)
                        .stepDetails(StepDetails.builder()
                                .result("\"cached-result\"")
                                .build())
                        .build());

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        var result = operation.get();
        assertEquals("cached-result", result);
    }

    @Test
    void getThrowsOriginalExceptionWhenClassIsAvailable() {
        var executionManager = createMockExecutionManager();
        var serDes = new JacksonSerDes();
        var originalException = new IllegalArgumentException("Invalid input");
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager,
                "java.lang.IllegalArgumentException",
                "Invalid input",
                serDes.serialize(originalException),
                stackTrace);

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                serDes);

        operation.execute();

        var thrown = assertThrows(IllegalArgumentException.class, operation::get);
        assertEquals("Invalid input", thrown.getMessage());
        assertEquals("com.example.Test", thrown.getStackTrace()[0].getClassName());
        assertEquals("method", thrown.getStackTrace()[0].getMethodName());
        assertEquals(42, thrown.getStackTrace()[0].getLineNumber());
    }

    @Test
    void getThrowsOriginalCustomExceptionWhenClassIsAvailable() {
        var executionManager = createMockExecutionManager();
        var serDes = new JacksonSerDes();
        var originalException = new CustomTestException("Custom error");
        var stackTrace = List.of("com.example.Handler|process|Handler.java|100");

        mockFailedOperation(
                executionManager,
                CustomTestException.class.getName(),
                "Custom error",
                serDes.serialize(originalException),
                stackTrace);

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                serDes);

        operation.execute();

        var thrown = assertThrows(CustomTestException.class, operation::get);
        assertEquals("Custom error", thrown.getMessage());
        assertEquals("com.example.Handler", thrown.getStackTrace()[0].getClassName());
    }

    @Test
    void getFallsBackToStepFailedExceptionWhenClassNotFound() {
        var executionManager = createMockExecutionManager();
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(executionManager, "NonExistentException", "This class doesn't exist", "{}", stackTrace);

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("NonExistentException"));
        assertTrue(thrown.getMessage().contains("This class doesn't exist"));
        assertEquals("com.example.Test", thrown.getStackTrace()[0].getClassName());
    }

    @Test
    void getFallsBackToStepFailedExceptionWhenDeserializationFails() {
        var executionManager = createMockExecutionManager();
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager,
                IllegalArgumentException.class.getName(),
                "Invalid input",
                "invalid-json-{{{",
                stackTrace);

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("IllegalArgumentException"));
        assertTrue(thrown.getMessage().contains("Invalid input"));
    }

    @Test
    void getFallsBackToStepFailedExceptionWhenErrorDataIsNull() {
        var executionManager = createMockExecutionManager();
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager, RuntimeException.class.getName(), "Something went wrong", null, stackTrace);

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("RuntimeException"));
        assertTrue(thrown.getMessage().contains("Something went wrong"));
    }

    @Test
    void getThrowsStepInterruptedExceptionDirectly() {
        var executionManager = createMockExecutionManager();
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager, StepInterruptedException.class.getName(), "Step was interrupted", null, stackTrace);

        var operation = new StepOperation<>(
                "1",
                "test-step",
                () -> "result",
                TypeToken.get(String.class),
                null,
                executionManager,
                mock(DurableLogger.class),
                new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepInterruptedException.class, operation::get);
        assertEquals("1", thrown.getOperationId());
        assertEquals("test-step", thrown.getStepName());
    }

    // Custom exception for testing
    public static class CustomTestException extends RuntimeException {
        public CustomTestException(String message) {
            super(message);
        }
    }
}
