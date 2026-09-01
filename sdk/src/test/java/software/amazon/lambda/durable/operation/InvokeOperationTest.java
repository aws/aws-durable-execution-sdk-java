// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.InvokeException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDesContext;

class InvokeOperationTest {
    private static final String OPERATION_ID = "2";
    private static final String OPERATION_NAME = "test-invoke";
    private static final OperationIdentifier OPERATION_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.CHAINED_INVOKE);

    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        TestUtils.configureSerDesRunner(executionManager);
        durableContext = mock(DurableContextImpl.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("root", ThreadType.CONTEXT));
    }

    @Test
    void getDoesNotThrowWhenCalledFromHandlerContext() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .result("\"cached-result\"")
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
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
    void invokePayloadAndResultUseDistinctPayloadEntityIds() {
        var contexts = new CopyOnWriteArrayList<SerDesContext>();
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value, SerDesContext context) {
                contexts.add(context);
                return super.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken, SerDesContext context) {
                contexts.add(context);
                return super.deserialize(data, typeToken);
            }
        };
        var completed = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .result("\"cached-result\"")
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(completed);
        when(executionManager.sendOperationUpdate(any())).thenReturn(CompletableFuture.completedFuture(null));
        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "test-function",
                "payload",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(serDes).build(),
                durableContext);

        operation.start();
        operation.onCheckpointComplete(completed);

        assertEquals("cached-result", operation.get());
        assertEquals(
                List.of("2/invoke-payload", "2/result"),
                contexts.stream().map(SerDesContext::entityId).toList());
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationFailed() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
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
                OPERATION_IDENTIFIER,
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
                .name(OPERATION_NAME)
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
                OPERATION_IDENTIFIER,
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
                .name(OPERATION_NAME)
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
                OPERATION_IDENTIFIER,
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
                .name(OPERATION_NAME)
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
                OPERATION_IDENTIFIER,
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                durableContext);
        operation.onCheckpointComplete(op);

        assertThrows(InvokeException.class, () -> operation.get());
    }
}
