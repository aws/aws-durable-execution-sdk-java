// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;
import software.amazon.lambda.durable.serde.SerDesStage;
import software.amazon.lambda.durable.serde.filesystem.FileSystemSerDesStage;

class StepOperationTest {

    private static final String DURABLE_EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test:1/durable-execution/execution/invocation";
    private static final String OPERATION_ID = "1";
    private static final String OPERATION_NAME = "test-step";
    private static final String RESULT = "result";
    private static final OperationIdentifier OPERATION_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.STEP);
    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    @TempDir
    Path basePath;

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

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(new JacksonSerDes()).build(),
                durableContext);
        operation.onCheckpointComplete(op);

        var result = operation.get();
        assertEquals("cached-result", result);
    }

    @Test
    void successfulReplayUsesCheckpointedAttemptInSerDesContext() {
        var observedContext = new AtomicReference<SerDesContext>();
        var serDes = new JacksonSerDes().then(new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                return value;
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                observedContext.set(context);
                return data;
            }
        });
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder()
                        .result("\"cached-result\"")
                        .attempt(3)
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(serDes).build(),
                durableContext);
        operation.onCheckpointComplete(op);

        assertEquals("cached-result", operation.get());
        assertEquals(3, observedContext.get().attempt());
        assertNull(observedContext.get().originalValue());
    }

    @Test
    void forwardedFilesystemExceptionIsReboundForFirstExecutionAndReplay() {
        when(executionManager.getDurableExecutionArn()).thenReturn(DURABLE_EXECUTION_ARN);
        var serDes =
                new JacksonSerDes().then(FileSystemSerDesStage.builder(basePath).build());
        var original = new IllegalArgumentException("callback failed");
        var forwarded = forwardedCallbackFailure(serDes, original);
        var failedUpdate = new AtomicReference<OperationUpdate>();
        doAnswer(invocation -> {
                    var update = invocation.<OperationUpdate>getArgument(0);
                    if (update.action() == OperationAction.FAIL) {
                        failedUpdate.set(update);
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .when(executionManager)
                .sendOperationUpdate(any());
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                ctx -> {
                    throw forwarded;
                },
                TypeToken.get(String.class),
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .serDes(serDes)
                        .build(),
                durableContext);

        operation.execute();

        verify(executionManager, timeout(5_000))
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
        var checkpointedError = failedUpdate.get().error();
        var stepContext = SerDesContext.forOperation(
                DURABLE_EXECUTION_ARN,
                OPERATION_ID,
                OPERATION_NAME,
                null,
                OperationType.STEP,
                OperationSubType.STEP,
                SerDesPayloadKind.EXCEPTION,
                1);
        var rebound = new SerDesRunner(null)
                .deserialize(
                        serDes,
                        checkpointedError.errorData(),
                        TypeToken.get(IllegalArgumentException.class),
                        stepContext);
        assertEquals("callback failed", rebound.getMessage());

        var replayedOperation = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType(OperationSubType.STEP.getValue())
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .attempt(1)
                        .error(checkpointedError)
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(replayedOperation);
        var replay = new StepOperation<>(
                OPERATION_IDENTIFIER,
                ctx -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(serDes).build(),
                durableContext);

        replay.execute();

        var thrown = assertThrows(IllegalArgumentException.class, replay::get);
        assertEquals("callback failed", thrown.getMessage());
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

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(serDes).build(),
                durableContext);

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

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(serDes).build(),
                durableContext);

        operation.execute();

        var thrown = assertThrows(CustomTestException.class, operation::get);
        assertEquals("Custom error", thrown.getMessage());
        assertEquals("com.example.Handler", thrown.getStackTrace()[0].getClassName());
    }

    @Test
    void getFallsBackToStepFailedExceptionWhenClassNotFound() {
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        mockFailedOperation(executionManager, "NonExistentException", "This class doesn't exist", "{}", stackTrace);

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(new JacksonSerDes()).build(),
                durableContext);

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

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(new JacksonSerDes()).build(),
                durableContext);

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

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(new JacksonSerDes()).build(),
                durableContext);

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

        var operation = new StepOperation<>(
                OPERATION_IDENTIFIER,
                (ctx) -> RESULT,
                TypeToken.get(String.class),
                StepConfig.builder().serDes(new JacksonSerDes()).build(),
                durableContext);

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

    private CallbackFailedException forwardedCallbackFailure(SerDes serDes, RuntimeException original) {
        var sourceContext = SerDesContext.forOperation(
                DURABLE_EXECUTION_ARN,
                "callback-1",
                "callback",
                null,
                OperationType.CALLBACK,
                OperationSubType.CALLBACK,
                SerDesPayloadKind.EXCEPTION,
                null);
        var error = ErrorObject.builder()
                .errorType(original.getClass().getName())
                .errorMessage(original.getMessage())
                .errorData(new SerDesRunner(null).serialize(serDes, original, sourceContext))
                .build();
        var sourceOperation = Operation.builder()
                .id("callback-1")
                .name("callback")
                .type(OperationType.CALLBACK)
                .subType(OperationSubType.CALLBACK.getValue())
                .status(OperationStatus.FAILED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .error(error)
                        .build())
                .build();
        return new CallbackFailedException(sourceOperation, original);
    }
}
