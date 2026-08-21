// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class StepPrimitiveTest {

    private static final String OPERATION_ID = "1";
    private static final String OPERATION_NAME = "test-step";
    private static final String RESULT = "result";
    private static final PrimitiveOperationIdentifier OPERATION_IDENTIFIER =
            PrimitiveOperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.STEP);
    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    @Test
    void exposesOnlyTheExtensionConstructor() {
        assertEquals(1, StepPrimitive.class.getDeclaredConstructors().length);
    }

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        durableContext = mock(DurableContextImpl.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("handler", ThreadType.CONTEXT));
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .build());
    }

    private void mockFailedOperation(
            ExecutionManager executionManager,
            String errorType,
            String errorMessage,
            String errorData,
            List<String> stackTrace) {
        var operation = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
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
    }

    private StepPrimitive<String> createOperation(SerDes serDes) {
        return new StepPrimitive<>(
                OPERATION_IDENTIFIER,
                ignored -> ExtensionStepResult.succeed(RESULT),
                TypeToken.get(String.class),
                ExtensionStepConfig.<String>builder().serDes(serDes).build(),
                durableContext);
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"cached-result\"").build())
                .build();
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("handler", ThreadType.CONTEXT));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = createOperation(new JacksonSerDes());
        operation.onCheckpointComplete(op);

        var result = operation.get();
        assertEquals("cached-result", result);
    }

    @Test
    void getThrowsOriginalExceptionWhenClassIsAvailable() {
        var serDes = new JacksonSerDes();
        var originalException = new IllegalArgumentException("Invalid input");
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager,
                "java.lang.IllegalArgumentException",
                "Invalid input",
                serDes.serialize(originalException),
                stackTrace);

        var operation = createOperation(serDes);

        operation.execute();

        var thrown = assertThrows(IllegalArgumentException.class, operation::get);
        assertEquals("Invalid input", thrown.getMessage());
        assertEquals("com.example.Test", thrown.getStackTrace()[0].getClassName());
        assertEquals("method", thrown.getStackTrace()[0].getMethodName());
        assertEquals(42, thrown.getStackTrace()[0].getLineNumber());
    }

    @Test
    void getThrowsOriginalCustomExceptionWhenClassIsAvailable() {
        var serDes = new JacksonSerDes();
        var originalException = new CustomTestException("Custom error");
        var stackTrace = List.of("com.example.Handler|process|Handler.java|100");

        mockFailedOperation(
                executionManager,
                CustomTestException.class.getName(),
                "Custom error",
                serDes.serialize(originalException),
                stackTrace);

        var operation = createOperation(serDes);

        operation.execute();

        var thrown = assertThrows(CustomTestException.class, operation::get);
        assertEquals("Custom error", thrown.getMessage());
        assertEquals("com.example.Handler", thrown.getStackTrace()[0].getClassName());
    }

    @Test
    void getFallsBackToStepFailedExceptionWhenClassNotFound() {
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(executionManager, "NonExistentException", "This class doesn't exist", "{}", stackTrace);

        var operation = createOperation(new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("NonExistentException"));
        assertTrue(thrown.getMessage().contains("This class doesn't exist"));
        assertEquals("com.example.Test", thrown.getStackTrace()[0].getClassName());
    }

    @Test
    void getFallsBackToStepFailedExceptionWhenDeserializationFails() {
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager,
                IllegalArgumentException.class.getName(),
                "Invalid input",
                "invalid-json-{{{",
                stackTrace);

        var operation = createOperation(new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("IllegalArgumentException"));
        assertTrue(thrown.getMessage().contains("Invalid input"));
    }

    @Test
    void getFallsBackToStepFailedExceptionWhenErrorDataIsNull() {
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager, RuntimeException.class.getName(), "Something went wrong", null, stackTrace);

        var operation = createOperation(new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("RuntimeException"));
        assertTrue(thrown.getMessage().contains("Something went wrong"));
    }

    @Test
    void getThrowsStepInterruptedExceptionDirectly() {
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(
                executionManager, StepInterruptedException.class.getName(), "Step was interrupted", null, stackTrace);

        var operation = createOperation(new JacksonSerDes());

        operation.execute();

        var thrown = assertThrows(StepInterruptedException.class, operation::get);
        assertEquals(OPERATION_ID, thrown.getOperation().id());
        assertEquals(OPERATION_NAME, thrown.getOperation().name());
    }

    // Custom exception for testing
    public static class CustomTestException extends RuntimeException {
        public CustomTestException(String message) {
            super(message);
        }
    }
}
