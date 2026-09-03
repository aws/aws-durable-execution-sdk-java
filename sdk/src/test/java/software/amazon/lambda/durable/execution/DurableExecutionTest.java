// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.TypeToken.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.RetryablePayloadOffloadException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.InvocationSource;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.offload.internal.ChainedInvokeOutputFrame;
import software.amazon.lambda.durable.offload.internal.ChainedInvokePayloadFrame;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class DurableExecutionTest {

    private static final String EXECUTION_OP_ID = "20dae574-53da-37a1-bfd5-b0e2e6ec715d";
    private static final String OPERATION_ID1 = TestUtils.hashOperationId("1");
    private static final String EXECUTION_NAME = "exec-name";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;
    private static final Instant EXECUTION_START_TIME = Instant.parse("2026-08-15T00:00:00Z");

    private DurableConfig configWithMockClient() {
        return DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .build();
    }

    @Test
    void testExecuteSuccess() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("test", String.class, stepCtx -> "Hello " + userInput),
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
        assertTrue(output.result().contains("Hello test-input"));
    }

    @Test
    void framedChainedInvokeInputUsesPayloadOffloaderWhenEnabled() {
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
        var callerContext = PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:caller:$LATEST/durable-execution/name/id",
                OperationIdentifier.of("invoke", "invoke", OperationSubType.CHAINED_INVOKE),
                null,
                SerDesPayloadKind.INVOKE_PAYLOAD,
                null);
        var encoded = new PayloadCodec(null).serialize("test-input", new JacksonSerDes(), offloader, callerContext);
        var executionOp = executionOp().toBuilder()
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload(ChainedInvokePayloadFrame.encode(encoded))
                        .build())
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloader(offloader)
                .withPayloadOffloaderForChainedInvokePayloads(true)
                .build();

        var output = DurableExecutor.execute(input, null, get(String.class), (userInput, ctx) -> userInput, config);
        var framedResult = ChainedInvokeOutputFrame.decode(output.result());
        var result = new PayloadCodec(null)
                .deserialize(
                        framedResult.payload(),
                        get(String.class),
                        new JacksonSerDes(),
                        offloader,
                        PayloadOffloadContext.forExecution(
                                EXECUTION_ARN, EXECUTION_OP_ID, EXECUTION_NAME, SerDesPayloadKind.OUTPUT));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(framedResult.usesPayloadCodec());
        assertEquals("test-input", result);
    }

    @Test
    void unframedInputDoesNotEnterPayloadOffloaderPipeline() {
        var loadCount = new int[1];
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount[0]++;
                return payload.data();
            }
        };
        var rawInput = new PayloadCodec(null)
                .serialize(
                        "domain-input",
                        new JacksonSerDes(),
                        offloader,
                        PayloadOffloadContext.forExecution(
                                "arn:aws:lambda:us-east-1:123456789012:function:caller:$LATEST/durable-execution/name/id",
                                "id",
                                "caller",
                                SerDesPayloadKind.INPUT));
        SerDes passThroughSerDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return (String) value;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, software.amazon.lambda.durable.TypeToken<T> typeToken) {
                return (T) data;
            }
        };
        var executionOp = executionOp().toBuilder()
                .executionDetails(
                        ExecutionDetails.builder().inputPayload(rawInput).build())
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withSerDes(passThroughSerDes)
                .withPayloadOffloader(offloader)
                .withPayloadOffloaderForChainedInvokePayloads(true)
                .build();
        var observedInput = new AtomicReference<String>();

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    observedInput.set(userInput);
                    return "result";
                },
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(rawInput, observedInput.get());
        assertEquals(0, loadCount[0]);
    }

    @Test
    void unframedChainedInvocationKeepsOutputOnOrdinaryWire() {
        var offloadCount = new AtomicInteger();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloader(countingOffloader(offloadCount))
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build(),
                List.of(),
                InvocationSource.CHAINED_INVOKE);

        var output = DurableExecutor.execute(input, null, get(String.class), (userInput, ctx) -> "result", config);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("\"result\"", output.result());
        assertEquals(0, offloadCount.get());
    }

    @Test
    void unframedChainedInvocationKeepsErrorOnOrdinaryWire() {
        var offloadCount = new AtomicInteger();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloader(countingOffloader(offloadCount))
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build(),
                List.of(),
                InvocationSource.CHAINED_INVOKE);

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    throw new IllegalStateException("failed");
                },
                config);

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertFalse(PayloadCodec.isOffloadEnvelope(output.error().errorData()));
        assertEquals(0, offloadCount.get());
    }

    @Test
    void framedInputRejectsUnsupportedPayloadEnvelopeVersion() {
        var executionOp = executionOp().toBuilder()
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload(ChainedInvokePayloadFrame.encode("@aws-durable-payload:v2:{}"))
                        .build())
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloaderForChainedInvokePayloads(true)
                .build();

        var output = DurableExecutor.execute(input, null, get(String.class), (userInput, ctx) -> userInput, config);

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertTrue(output.error().errorMessage().contains("Unsupported or malformed"));
    }

    @Test
    void framedChainedInvokeFailureMarksExternalErrorDataAsRaw() {
        var marker = "@aws-durable-payload:v2:{}";
        var executionOp = executionOp().toBuilder()
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload(ChainedInvokePayloadFrame.encode("\"test-input\""))
                        .build())
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build(),
                List.of(),
                InvocationSource.CHAINED_INVOKE);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloaderForChainedInvokePayloads(true)
                .build();
        var remoteError = ErrorObject.builder()
                .errorType("RemoteError")
                .errorMessage("remote failure")
                .errorData(marker)
                .build();
        var operation = Operation.builder()
                .id("invoke")
                .type(OperationType.CHAINED_INVOKE)
                .status(OperationStatus.FAILED)
                .build();

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    throw new DurableOperationException(operation, remoteError);
                },
                config);
        var decodedError = ChainedInvokeOutputFrame.decode(output.error().errorData());

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals(marker, decodedError.payload());
        assertFalse(decodedError.usesPayloadCodec());
    }

    @Test
    void permanentRootOutputOffloadFailureProducesFailedOutputAndPluginEnd() {
        var statuses = new CopyOnWriteArrayList<InvocationStatus>();
        var config = configWithFailingPayloadKind(SerDesPayloadKind.OUTPUT, false, statuses);
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(input, null, get(String.class), (userInput, ctx) -> "result", config);

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals(PayloadOffloadException.class.getName(), output.error().errorType());
        assertEquals(List.of(InvocationStatus.FAILED), statuses);
    }

    @Test
    void retryableRootOutputOffloadFailureRetriesInvocationAndPluginEnd() {
        var statuses = new CopyOnWriteArrayList<InvocationStatus>();
        var config = configWithFailingPayloadKind(SerDesPayloadKind.OUTPUT, true, statuses);
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        assertThrows(
                RetryablePayloadOffloadException.class,
                () -> DurableExecutor.execute(input, null, get(String.class), (userInput, ctx) -> "result", config));
        assertEquals(List.of(InvocationStatus.RETRYING), statuses);
    }

    @Test
    void rootOutputSerDesFailureEscapesInvocation() {
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if ("result".equals(value)) {
                    throw new SerDesException("cannot serialize root output");
                }
                return super.serialize(value);
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withSerDes(serDes)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var failure = assertThrows(
                SerDesException.class,
                () -> DurableExecutor.execute(input, null, get(String.class), (userInput, ctx) -> "result", config));

        assertEquals("cannot serialize root output", failure.getMessage());
    }

    @Test
    void errorDataOffloadFailureIsClassifiedBeforePluginEnd() {
        var statuses = new CopyOnWriteArrayList<InvocationStatus>();
        var config = configWithFailingPayloadKind(SerDesPayloadKind.EXCEPTION, false, statuses);
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    throw new IllegalStateException("user failure");
                },
                config);

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals(PayloadOffloadException.class.getName(), output.error().errorType());
        assertEquals(List.of(InvocationStatus.FAILED), statuses);
    }

    @Test
    void errorSerDesFailureStillFiresInvocationEnd() {
        var statuses = new CopyOnWriteArrayList<InvocationStatus>();
        var reportedError = new AtomicReference<Throwable>();
        var original = new IllegalStateException("user failure");
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if (value == original) {
                    throw new SerDesException("cannot serialize handler failure");
                }
                return super.serialize(value);
            }
        };
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                statuses.add(info.invocationStatus());
                reportedError.set(info.executionError());
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withSerDes(serDes)
                .withPlugins(plugin)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var failure = assertThrows(
                SerDesException.class,
                () -> DurableExecutor.execute(
                        input,
                        null,
                        get(String.class),
                        (userInput, ctx) -> {
                            throw original;
                        },
                        config));

        assertEquals("cannot serialize handler failure", failure.getMessage());
        assertEquals(List.of(InvocationStatus.FAILED), statuses);
        assertEquals(original, reportedError.get());
    }

    @Test
    void operationErrorIsReboundFromOverrideOffloaderToGlobalOffloader() {
        var sourceLoads = new AtomicInteger();
        var targetOffloads = new AtomicInteger();
        var sourceOffloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.inline(
                        Base64.getEncoder().encodeToString(serializedPayload.getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                sourceLoads.incrementAndGet();
                return new String(Base64.getDecoder().decode(payload.data()), StandardCharsets.UTF_8);
            }
        };
        var targetOffloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                targetOffloads.incrementAndGet();
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        var producerContext = PayloadOffloadContext.forOperation(
                EXECUTION_ARN,
                OperationIdentifier.of("inner-step", "inner", OperationSubType.STEP),
                null,
                SerDesPayloadKind.EXCEPTION,
                1);
        var sourcePayload = new PayloadCodec(null)
                .serialize(
                        new IllegalStateException("nested failure"),
                        new JacksonSerDes(),
                        sourceOffloader,
                        producerContext);
        var error = ErrorObject.builder()
                .errorType(IllegalStateException.class.getName())
                .errorMessage("nested failure")
                .errorData(sourcePayload)
                .build();
        var operation = Operation.builder()
                .id("inner-step")
                .type(OperationType.STEP)
                .status(OperationStatus.FAILED)
                .build();
        var operationFailure =
                new DurableOperationException(operation, error).withPayloadSource(sourceOffloader, producerContext);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloader(targetOffloader)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    throw operationFailure;
                },
                config);
        var restored = new PayloadCodec(null)
                .deserialize(
                        output.error().errorData(),
                        get(IllegalStateException.class),
                        new JacksonSerDes(),
                        targetOffloader,
                        PayloadOffloadContext.forExecution(
                                EXECUTION_ARN, EXECUTION_OP_ID, EXECUTION_NAME, SerDesPayloadKind.EXCEPTION));

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals("nested failure", restored.getMessage());
        assertEquals(1, sourceLoads.get());
        assertEquals(1, targetOffloads.get());
    }

    @Test
    void rawOperationErrorIsOffloadedAtExecutionBoundary() {
        var targetOffloads = new AtomicInteger();
        var storedPayload = new AtomicReference<String>();
        var targetOffloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                targetOffloads.incrementAndGet();
                storedPayload.set(serializedPayload);
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        var error = ErrorObject.builder()
                .errorType("RemoteError")
                .errorMessage("remote failure")
                .errorData("raw-error-data")
                .build();
        var operation = Operation.builder()
                .id("invoke")
                .type(OperationType.CHAINED_INVOKE)
                .status(OperationStatus.FAILED)
                .build();
        var operationFailure = new DurableOperationException(operation, error);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloader(targetOffloader)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    throw operationFailure;
                },
                config);
        var context = PayloadOffloadContext.forExecution(
                EXECUTION_ARN, EXECUTION_OP_ID, EXECUTION_NAME, SerDesPayloadKind.EXCEPTION);
        var restored =
                new PayloadCodec(null).resolveSerializedPayload(output.error().errorData(), targetOffloader, context);

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals("raw-error-data", restored);
        assertEquals("raw-error-data", storedPayload.get());
        assertEquals(1, targetOffloads.get());
    }

    @Test
    void largeRootOutputCheckpointTransportFailureEscapesForInvocationRetry() {
        var client = TestUtils.createMockClient();
        when(client.checkpoint(any(), any(), any())).thenThrow(new IllegalStateException("transport unavailable"));
        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());
        var largeResult = "x".repeat(6 * 1024 * 1024);

        var failure = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(input, null, get(String.class), (userInput, ctx) -> largeResult, config));

        assertEquals("transport unavailable", failure.getMessage());
    }

    @Test
    void testExecutePending() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    ctx.step("step1", String.class, stepCtx -> "Done");
                    ctx.wait(null, java.time.Duration.ofSeconds(60));
                    return "Should not reach here";
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
    }

    @Test
    void waiterFirstTerminalCheckpointReturnsSuccessfulOutput() {
        var pendingStep = Operation.builder()
                .id("step")
                .name("step")
                .type(OperationType.STEP)
                .subType(OperationSubType.STEP.getValue())
                .status(OperationStatus.PENDING)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp(), pendingStep))
                        .build());
        var checkpointAttempted = new CountDownLatch(1);
        var checkpointFuture = new AtomicReference<CompletableFuture<Void>>();

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    var durableContext = (DurableContextImpl) ctx;
                    var manager = durableContext.getExecutionManager();

                    class TestOperation extends BaseDurableOperation {
                        TestOperation() {
                            super(OperationIdentifier.of("step", "step", OperationSubType.STEP), durableContext, null);
                        }

                        @Override
                        protected void start() {}

                        @Override
                        protected void replay(Operation existing) {}

                        Operation awaitCompletion() {
                            return waitForOperationCompletion();
                        }

                        @Override
                        protected void deregisterActiveThread(String threadId) {
                            checkpointFuture.set(CompletableFuture.runAsync(() -> {
                                checkpointAttempted.countDown();
                                try {
                                    manager.onCheckpointComplete(List.of(Operation.builder()
                                            .id("step")
                                            .name("step")
                                            .type(OperationType.STEP)
                                            .subType(OperationSubType.STEP.getValue())
                                            .status(OperationStatus.SUCCEEDED)
                                            .build()));
                                } finally {
                                    manager.finishCheckpointProcessing();
                                }
                            }));
                            try {
                                assertTrue(checkpointAttempted.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(e);
                            }
                            super.deregisterActiveThread(threadId);
                        }
                    }

                    var operation = new TestOperation();
                    operation.execute();
                    assertTrue(manager.tryStartCheckpointProcessing());
                    var completed = operation.awaitCompletion();
                    checkpointFuture.get().join();
                    return completed.statusAsString();
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(output.result().contains(OperationStatus.SUCCEEDED.toString()));
    }

    @Test
    void testExecuteFailure() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    throw new RuntimeException("Test error");
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertNotNull(output.error());
        assertEquals("java.lang.RuntimeException", output.error().errorType());
        assertEquals("Test error", output.error().errorMessage());
    }

    @Test
    void testRetryableExceptions() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        UnrecoverableDurableExecutionException ex = assertThrows(
                UnrecoverableDurableExecutionException.class,
                () -> DurableExecutor.execute(
                        input,
                        null,
                        get(String.class),
                        (userInput, ctx) -> {
                            throw new UnrecoverableDurableExecutionException(
                                    ErrorObject.builder()
                                            .errorMessage("Test error")
                                            .build(),
                                    true);
                        },
                        configWithMockClient()));

        assertTrue(ex.isRetryable());
    }

    @Test
    void testExecuteReplay() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var completedStep = Operation.builder()
                .id(OPERATION_ID1)
                .name("step1")
                .type(OperationType.STEP)
                .subType(OperationSubType.STEP.getValue())
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"First\"").build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token2",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp, completedStep))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("step1", String.class, stepCtx -> "Second"),
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(output.result().contains("First"));
    }

    @Test
    void testValidationNoOperations() {
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder().operations(List.of()).build());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(
                        input, null, get(String.class), (userInput, ctx) -> "result", configWithMockClient()));

        assertEquals("EXECUTION operation not found", exception.getMessage());
    }

    @Test
    void testValidationWrongFirstOperation() {
        var stepOp = Operation.builder()
                .id(OPERATION_ID1)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(stepOp))
                        .build());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(
                        input, null, get(String.class), (userInput, ctx) -> "result", configWithMockClient()));

        assertEquals("EXECUTION operation not found", exception.getMessage());
    }

    @Test
    void testValidationMissingExecutionDetails() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var result = DurableExecutor.execute(
                input, null, get(String.class), (userInput, ctx) -> "result", configWithMockClient());

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(
                "EXECUTION operation missing executionDetails", result.error().errorMessage());
    }

    @Test
    void testExecutorNotShutdownAfterMultipleHandlerInvocations() {
        // Create a config with a shared executor
        var config = configWithMockClient();
        ExecutorService sharedExecutor = config.getExecutorService();

        // Verify executor is not shutdown initially
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown initially");

        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input-1\"")
                        .build())
                .build();

        var input1 = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        // Execute first handler
        var output1 = DurableExecutor.execute(
                input1,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("test1", String.class, stepCtx -> "Result 1: " + userInput),
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output1.status());
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown after first execution");

        // Create second input with different execution operation
        var executionOp2 = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input-2\"")
                        .build())
                .build();

        var input2 = new DurableExecutionInput(
                EXECUTION_ARN,
                "token2",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp2))
                        .build());

        // Execute second handler using the same config (and thus same executor)
        var output2 = DurableExecutor.execute(
                input2,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("test2", String.class, stepCtx -> "Result 2: " + userInput),
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output2.status());
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown after second execution");

        // Verify both executions completed successfully and used the same executor
        assertTrue(output1.result().contains("Result 1: test-input-1"));
        assertTrue(output2.result().contains("Result 2: test-input-2"));
    }

    private Operation executionOp() {
        return Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();
    }

    private static PayloadOffloader countingOffloader(AtomicInteger offloadCount) {
        return new PayloadOffloader() {
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
    }

    private DurableConfig configWithFailingPayloadKind(
            SerDesPayloadKind failingKind, boolean retryable, List<InvocationStatus> statuses) {
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                if (context.payloadKind() == failingKind) {
                    if (retryable) {
                        throw new RetryablePayloadOffloadException("storage unavailable");
                    }
                    throw new PayloadOffloadException("storage unavailable");
                }
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                statuses.add(info.invocationStatus());
            }
        };
        return DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPayloadOffloader(offloader)
                .withPlugins(plugin)
                .build();
    }
}
