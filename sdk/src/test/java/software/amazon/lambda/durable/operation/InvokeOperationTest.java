// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.InvokeException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.PayloadCodec;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.offload.internal.ChainedInvokeOutputFrame;
import software.amazon.lambda.durable.offload.internal.ChainedInvokePayloadFrame;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

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
    void standardLambdaMarkerResultRemainsExternalData() {
        var marker = "@aws-durable-payload:v2:{}";
        var loadCount = new AtomicInteger();
        var offloader = countingOffloader(loadCount);
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .chainedInvokeDetails(
                        ChainedInvokeDetails.builder().result(marker).build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);
        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "standard-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new PassThroughSerDes())
                        .payloadOffloader(offloader)
                        .build(),
                durableContext);
        operation.onCheckpointComplete(op);

        assertEquals(marker, operation.get());
        assertEquals(0, loadCount.get());
    }

    @Test
    void standardLambdaMarkerErrorDoesNotAttachPayloadSource() {
        var marker = "@aws-durable-payload:v2:{}";
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("RemoteError")
                                .errorMessage("remote failure")
                                .errorData(marker)
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);
        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "standard-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new PassThroughSerDes())
                        .payloadOffloader(countingOffloader(new AtomicInteger()))
                        .build(),
                durableContext);
        operation.onCheckpointComplete(op);

        var failure = assertThrows(InvokeFailedException.class, operation::get);

        assertEquals(marker, failure.getErrorObject().errorData());
        assertNull(failure.getPayloadOffloadContext());
    }

    @Test
    void durableTargetRawMarkerErrorRemainsExternalData() {
        var marker = "@aws-durable-payload:v2:{}";
        var framedError = ChainedInvokeOutputFrame.encode(marker, false);
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("RemoteError")
                                .errorMessage("remote failure")
                                .errorData(framedError)
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);
        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "durable-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new PassThroughSerDes())
                        .payloadOffloader(countingOffloader(new AtomicInteger()))
                        .usePayloadOffloaderForPayload(true)
                        .build(),
                durableContext);
        operation.onCheckpointComplete(op);

        var failure = assertThrows(InvokeFailedException.class, operation::get);

        assertEquals(marker, failure.getErrorObject().errorData());
        assertNull(failure.getPayloadOffloadContext());
    }

    @Test
    void malformedCodecResultFrameFailsClosed() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.SUCCEEDED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .result(ChainedInvokeOutputFrame.encode("\"ordinary-json\"", true))
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);
        configurePayloadCodec();
        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "durable-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new JacksonSerDes())
                        .usePayloadOffloaderForPayload(true)
                        .build(),
                durableContext);
        operation.onCheckpointComplete(op);

        assertThrows(PayloadOffloadException.class, operation::get);
    }

    @Test
    void malformedCodecErrorFrameFailsClosed() {
        var op = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("RemoteError")
                                .errorMessage("remote failure")
                                .errorData(ChainedInvokeOutputFrame.encode("ordinary-error", true))
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);
        configurePayloadCodec();
        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "durable-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new PassThroughSerDes())
                        .usePayloadOffloaderForPayload(true)
                        .build(),
                durableContext);
        operation.onCheckpointComplete(op);

        assertThrows(PayloadOffloadException.class, operation::get);
    }

    @Test
    void codecFramedErrorLoadsReferenceBeforeThrowing() {
        configurePayloadCodec();
        var stored = new AtomicReference<String>();
        var loadCount = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                stored.set(serializedPayload);
                return OffloadedPayload.reference("memory://invoke-error", null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount.incrementAndGet();
                return stored.get();
            }
        };
        var codec = executionManager.getPayloadCodec();
        var payload = codec.serializePreEncodedPayload("serialized-error", offloader, invokeErrorContext());
        var op = failedInvoke(ChainedInvokeOutputFrame.encode(payload, true));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);
        var operation = invokeOperation(offloader);
        operation.onCheckpointComplete(op);

        var failure = assertThrows(InvokeFailedException.class, operation::get);

        assertEquals("serialized-error", failure.getErrorObject().errorData());
        assertEquals(1, loadCount.get());
        assertNull(failure.getPayloadOffloadContext());
    }

    @Test
    void codecFramedErrorRejectsTamperedReferenceContent() {
        configurePayloadCodec();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.reference("memory://invoke-error", null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return "tampered";
            }
        };
        var payload = executionManager
                .getPayloadCodec()
                .serializePreEncodedPayload("serialized-error", offloader, invokeErrorContext());
        var op = failedInvoke(ChainedInvokeOutputFrame.encode(payload, true));
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(op);
        var operation = invokeOperation(offloader);
        operation.onCheckpointComplete(op);

        assertThrows(PayloadOffloadException.class, operation::get);
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

    @Test
    void invokeRequestPayloadIsNotOffloaded() {
        var offloadCount = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                offloadCount.incrementAndGet();
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);
        when(executionManager.sendOperationUpdate(any())).thenReturn(CompletableFuture.completedFuture(null));

        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "test-function",
                new InvokePayload("request"),
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new JacksonSerDes())
                        .payloadOffloader(offloader)
                        .build(),
                durableContext);

        operation.execute();

        verify(executionManager)
                .sendOperationUpdate(argThat(update -> "{\"value\":\"request\"}".equals(update.payload())));
        assertEquals(0, offloadCount.get());
    }

    @Test
    void invokeRequestCanExplicitlyUsePayloadOffloader() {
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);
        when(executionManager.sendOperationUpdate(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(executionManager.getPayloadCodec()).thenReturn(new PayloadCodec(null));
        when(executionManager.getDurableExecutionArn())
                .thenReturn("arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/name/id");

        var operation = new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "test-function",
                new InvokePayload("request"),
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new JacksonSerDes())
                        .payloadOffloader(offloader)
                        .usePayloadOffloaderForPayload(true)
                        .build(),
                durableContext);

        operation.execute();

        verify(executionManager).sendOperationUpdate(argThat(update -> {
            var payload = update.payload();
            return ChainedInvokePayloadFrame.isFramed(payload)
                    && ChainedInvokePayloadFrame.decode(payload).startsWith("@aws-durable-payload:v1:");
        }));
    }

    private static PayloadOffloader countingOffloader(AtomicInteger loadCount) {
        return new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount.incrementAndGet();
                return payload.data();
            }
        };
    }

    private void configurePayloadCodec() {
        when(executionManager.getPayloadCodec()).thenReturn(new PayloadCodec(null));
        when(executionManager.getDurableExecutionArn())
                .thenReturn("arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/name/id");
    }

    private InvokeOperation<String, String> invokeOperation(PayloadOffloader offloader) {
        return new InvokeOperation<>(
                OPERATION_IDENTIFIER,
                "durable-function",
                "{}",
                TypeToken.get(String.class),
                InvokeConfig.builder()
                        .serDes(new PassThroughSerDes())
                        .payloadOffloader(offloader)
                        .usePayloadOffloaderForPayload(true)
                        .build(),
                durableContext);
    }

    private static Operation failedInvoke(String errorData) {
        return Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .status(OperationStatus.FAILED)
                .chainedInvokeDetails(ChainedInvokeDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("RemoteError")
                                .errorMessage("remote failure")
                                .errorData(errorData)
                                .build())
                        .build())
                .build();
    }

    private PayloadOffloadContext invokeErrorContext() {
        return PayloadOffloadContext.forOperation(
                executionManager.getDurableExecutionArn(),
                OPERATION_IDENTIFIER,
                null,
                SerDesPayloadKind.EXCEPTION,
                null);
    }

    private static final class PassThroughSerDes implements SerDes {
        @Override
        public String serialize(Object value) {
            return (String) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return (T) data;
        }
    }

    private record InvokePayload(String value) {}
}
