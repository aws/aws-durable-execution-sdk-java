// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
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
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;
import software.amazon.lambda.durable.serde.filesystem.FileSystemSerDesStage;

class InvokeOperationTest {
    private static final String OPERATION_ID = "2";
    private static final String OPERATION_NAME = "test-invoke";
    private static final OperationIdentifier OPERATION_IDENTIFIER =
            OperationIdentifier.of(OPERATION_ID, OPERATION_NAME, OperationSubType.CHAINED_INVOKE);
    private static final AtomicInteger EXTERNALLY_SELECTED_EXCEPTION_INITIALIZATIONS = new AtomicInteger();

    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    static final class ExternallySelectedException extends RuntimeException {
        static {
            EXTERNALLY_SELECTED_EXCEPTION_INITIALIZATIONS.incrementAndGet();
        }
    }

    private static final class TrackingSerDes extends JacksonSerDes {
        private final AtomicInteger deserializeCount = new AtomicInteger();

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            deserializeCount.incrementAndGet();
            return super.deserialize(data, typeToken);
        }
    }

    @TempDir
    Path basePath;

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
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
    void getInvokeFailedExceptionKeepsRemoteErrorOpaque() {
        var serDes = new TrackingSerDes();
        var errorType = InvokeOperationTest.class.getName() + "$ExternallySelectedException";
        var errorData = "{}";
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType(errorType)
                                .errorMessage("errorMessage")
                                .errorData(errorData)
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(serDes).build(),
                durableContext);
        operation.onCheckpointComplete(op);

        InvokeFailedException ex = assertThrows(InvokeFailedException.class, () -> operation.get());
        assertEquals(errorData, ex.getErrorObject().errorData());
        assertEquals(errorType, ex.getErrorObject().errorType());
        assertEquals("errorMessage", ex.getMessage());
        assertNull(ex.deserializedError());
        assertNull(ex.getCause());
        assertEquals(0, EXTERNALLY_SELECTED_EXCEPTION_INITIALIZATIONS.get());
        assertEquals(0, serDes.deserializeCount.get());
    }

    @Test
    void getInvokeFailedExceptionWhenInvocationDetailsAreMissing() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
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

        var exception = assertThrows(InvokeFailedException.class, operation::get);
        assertNull(exception.getErrorObject());
        assertNull(exception.deserializedError());
    }

    @Test
    void getInvokeFailedExceptionWhenErrorTypeIsMissing() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorMessage("untyped failure")
                                .errorData("not-json")
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

        var exception = assertThrows(InvokeFailedException.class, operation::get);
        assertEquals("untyped failure", exception.getMessage());
        assertNull(exception.getErrorObject().errorType());
        assertNull(exception.deserializedError());
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

    @ParameterizedTest
    @EnumSource(
            value = OperationStatus.class,
            names = {"TIMED_OUT", "STOPPED"})
    void nestedTerminalInvokeKeepsRemoteFilesystemErrorOpaque(OperationStatus status) {
        var calleeArn = "arn:aws:lambda:us-east-1:123456789012:function:callee/durable-execution/callee/invocation";

        var serDes =
                new JacksonSerDes().then(FileSystemSerDesStage.builder(basePath).build());
        var original = new IllegalStateException("callee failed");
        var errorData = new SerDesRunner(null)
                .serialize(
                        serDes,
                        original,
                        SerDesContext.forExecution(
                                calleeArn, "callee-invocation", "callee-execution", SerDesPayloadKind.EXCEPTION));
        var remoteError = ErrorObject.builder()
                .errorType(original.getClass().getName())
                .errorMessage(original.getMessage())
                .errorData(errorData)
                .build();
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(status)
                .chainedInvokeDetails(
                        ChainedInvokeDetails.builder().error(remoteError).build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);

        var invoke = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "test-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(serDes).build(),
                durableContext);
        invoke.onCheckpointComplete(op);

        DurableOperationException forwarded = status == OperationStatus.TIMED_OUT
                ? assertThrows(InvokeTimedOutException.class, invoke::get)
                : assertThrows(InvokeStoppedException.class, invoke::get);
        assertNull(forwarded.deserializedError());

        var child = new ChildContextRebindingOperation(serDes, durableContext);
        var rebound = child.rebind(forwarded);
        assertEquals(remoteError, rebound);
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

    private static final class ChildContextRebindingOperation extends SerializableDurableOperation<String> {
        private ChildContextRebindingOperation(SerDes serDes, DurableContextImpl durableContext) {
            super(
                    OperationIdentifier.of("child", "child", OperationSubType.RUN_IN_CHILD_CONTEXT),
                    TypeToken.get(String.class),
                    serDes,
                    durableContext);
        }

        private ErrorObject rebind(DurableOperationException exception) {
            return rebindForwardedException(exception);
        }

        @Override
        protected void start() {}

        @Override
        protected void replay(Operation existing) {}

        @Override
        public String get() {
            return null;
        }
    }
}
