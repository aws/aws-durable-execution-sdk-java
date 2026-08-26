// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.serde.Base64StringBinaryCodec;
import software.amazon.lambda.durable.serde.ComposableBinarySerDesStage;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;
import software.amazon.lambda.durable.serde.SerDesStage;
import software.amazon.lambda.durable.serde.Utf8StringBinaryCodec;
import software.amazon.lambda.durable.serde.filesystem.FileSystemSerDesStage;
import software.amazon.lambda.durable.serde.internal.ChainedInvokePayloadFrame;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestResult;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.testing.local.OperationResult;

class FileSystemSerDesStageIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path basePath;

    @Test
    void pipelineReplaysStepWaitChildAndMapPayloadsFromFilesystem() throws Exception {
        var stepExecutions = new AtomicInteger();
        var pollExecutions = new AtomicInteger();
        var serDes = filesystemPipeline();
        var config = DurableConfig.builder().withSerDes(serDes).build();

        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            var stepResult = context.step("load-order", String.class, stepContext -> {
                                stepExecutions.incrementAndGet();
                                return input + "-loaded";
                            });
                            var waitConfig = WaitForConditionConfig.<Integer>builder()
                                    .waitStrategy(WaitStrategies.exponentialBackoff(
                                            5, Duration.ofSeconds(1), Duration.ofSeconds(10), 1, JitterStrategy.NONE))
                                    .build();
                            var pollResult = context.waitForCondition(
                                    "poll-order",
                                    Integer.class,
                                    (state, stepContext) -> {
                                        pollExecutions.incrementAndGet();
                                        var next = state == null ? 1 : state + 1;
                                        return next == 2
                                                ? WaitForConditionResult.stopPolling(next)
                                                : WaitForConditionResult.continuePolling(next);
                                    },
                                    waitConfig);
                            var childResult = context.runInChildContext(
                                    "format-order", String.class, child -> stepResult + "-child");
                            var mapResult = context.map(
                                    "map-order", List.of(1, 2), Integer.class, (item, index, child) -> item * 2);
                            return childResult + "-" + pollResult + "-" + mapResult.results();
                        },
                        config)
                .withOutputType(String.class);

        var result = runner.runUntilComplete("order");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("order-loaded-child-2-[2, 4]", result.getResult());
        assertEquals(1, stepExecutions.get());
        assertEquals(2, pollExecutions.get());
        assertEquals("order-loaded", result.getOperation("load-order").getStepResult(String.class));
        assertEnvelopePointsToFile(
                result.getOperation("load-order").getStepDetails().result());
        assertEnvelopePointsToFile(
                result.getOperation("map-order").getContextDetails().result());
    }

    @Test
    void durableExecutorAcceptsRawServiceInputBeforeFilesystemEnvelopeExists() {
        var executionArn =
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/execution/raw-input";
        var invocationId = "raw-input";
        var executionName = "execution";
        var executionOperation = Operation.builder()
                .id(invocationId)
                .name(executionName)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.now())
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"service-input\"")
                        .build())
                .build();
        var input = new DurableExecutionInput(
                executionArn,
                "checkpoint-token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(executionOperation)
                        .build(),
                List.of());
        var client = new LocalMemoryExecutionClient();
        var serDes = filesystemPipeline();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withSerDes(serDes)
                .build();

        var output = DurableExecutor.execute(
                input, null, TypeToken.get(String.class), (value, context) -> value + "-output", config);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        var result = new SerDesRunner(null)
                .deserialize(
                        serDes,
                        output.result(),
                        TypeToken.get(String.class),
                        SerDesContext.forExecution(
                                executionArn, invocationId, executionName, SerDesPayloadKind.OUTPUT));
        assertEquals("service-input-output", result);
    }

    @Test
    void acceptsRawCallbackAndInvokeResultsAndOffloadsInvokePayload() throws Exception {
        var invokePayload = new AtomicReference<String>();
        var recordingStage = identityStage((action, value, context) -> {
            if ("serialize".equals(action) && context.payloadKind() == SerDesPayloadKind.INVOKE_PAYLOAD) {
                invokePayload.set(value);
            }
        });
        var serDes = new JacksonSerDes()
                .then(recordingStage)
                .then(FileSystemSerDesStage.builder(basePath).build());
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            var callback = context.createCallback("approval", String.class);
                            var approval = callback.get();
                            return context.invoke(
                                    "notify",
                                    "target-function",
                                    Map.of("approval", approval),
                                    String.class,
                                    InvokeConfig.builder()
                                            .usePersistedSerDesForPayload(true)
                                            .build());
                        },
                        config)
                .withOutputType(String.class);

        var waitingForCallback = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, waitingForCallback.getStatus());
        var callbackId = runner.getCallbackId("approval");
        assertNotNull(callbackId);

        runner.completeCallback(callbackId, "\"approved\"");
        var waitingForInvoke = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, waitingForInvoke.getStatus());
        assertEquals(
                "approved", MAPPER.readTree(invokePayload.get()).get("approval").textValue());

        runner.completeChainedInvoke("notify", "\"notified\"");
        var completed = runner.run("input");

        assertEquals(ExecutionStatus.SUCCEEDED, completed.getStatus());
        assertEquals("notified", completed.getResult());
    }

    @Test
    void callerAndCalleeExchangeOffloadedInvokePayloadAndResult() throws Exception {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var serDes = filesystemPipeline();
        var callerClient = new LocalMemoryExecutionClient();
        var callerConfig = DurableConfig.builder()
                .withDurableExecutionClient(callerClient)
                .withSerDes(serDes)
                .build();
        BiFunction<String, DurableContext, CrossInvokeResponse> callerHandler = (input, context) -> context.invoke(
                "call-callee",
                "callee",
                new CrossInvokeRequest(input),
                CrossInvokeResponse.class,
                InvokeConfig.builder().usePersistedSerDesForPayload(true).build());
        var callerExecution =
                executionOperation("caller-invocation", "caller-execution", "\"request\"", OperationStatus.STARTED);

        var pending = DurableExecutor.execute(
                durableInput(callerArn, callerExecution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);

        assertEquals(ExecutionStatus.PENDING, pending.status());
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();
        assertEnvelopePointsToFile(ChainedInvokePayloadFrame.decode(invokePayload));

        var calleeClient = new LocalMemoryExecutionClient();
        var calleeConfig = DurableConfig.builder()
                .withDurableExecutionClient(calleeClient)
                .withSerDes(serDes)
                .build();
        var calleeExecution =
                executionOperation("callee-invocation", "callee-execution", invokePayload, OperationStatus.STARTED);
        var calleeOutput = DurableExecutor.execute(
                durableInput(calleeArn, calleeExecution, List.of(), List.of()),
                null,
                TypeToken.get(CrossInvokeRequest.class),
                (request, context) -> new CrossInvokeResponse("reply:" + request.value()),
                calleeConfig);

        assertEquals(ExecutionStatus.SUCCEEDED, calleeOutput.status());
        assertEnvelopePointsToFile(calleeOutput.result());

        callerClient.completeChainedInvoke("call-callee", OperationResult.succeeded(calleeOutput.result()));
        var resumed = DurableExecutor.execute(
                durableInput(
                        callerArn,
                        callerExecution,
                        callerClient.getAllOperations(),
                        callerClient.getUpdatedOperationIdsSinceLastInvocation()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);

        assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
        var result = new SerDesRunner(null)
                .deserialize(
                        serDes,
                        resumed.result(),
                        TypeToken.get(CrossInvokeResponse.class),
                        SerDesContext.forExecution(
                                callerArn, "caller-invocation", "caller-execution", SerDesPayloadKind.OUTPUT));
        assertEquals(new CrossInvokeResponse("reply:request"), result);
    }

    @Test
    void defaultInvokePayloadPreservesStandardAndLegacyJavaWireContracts() {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var callerClient = new LocalMemoryExecutionClient();
        var callerConfig = DurableConfig.builder()
                .withDurableExecutionClient(callerClient)
                .withSerDes(filesystemPipeline())
                .build();
        BiFunction<String, DurableContext, String> callerHandler = (input, context) ->
                context.invoke("call-standard", "standard", new CrossInvokeRequest(input), String.class);
        var callerExecution =
                executionOperation("caller-invocation", "caller-execution", "\"request\"", OperationStatus.STARTED);

        var pending = DurableExecutor.execute(
                durableInput(callerArn, callerExecution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);

        assertEquals(ExecutionStatus.PENDING, pending.status());
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();
        assertEquals("{\"value\":\"request\"}", invokePayload);
        assertEquals(
                new CrossInvokeRequest("request"),
                new JacksonSerDes().deserialize(invokePayload, TypeToken.get(CrossInvokeRequest.class)));
    }

    @Test
    void repeatedGetUsesInvocationCacheForTheCompletePipeline() {
        var resultDeserializations = new AtomicInteger();
        var countingStage = identityStage((action, value, context) -> {
            if ("deserialize".equals(action)
                    && context.payloadKind() == SerDesPayloadKind.RESULT
                    && "cached-step".equals(context.operationName())) {
                resultDeserializations.incrementAndGet();
            }
        });
        var serDes = new JacksonSerDes()
                .then(countingStage)
                .then(FileSystemSerDesStage.builder(basePath).build());
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            var future =
                                    context.stepAsync("cached-step", Payload.class, stepContext -> new Payload(input));
                            var first = future.get();
                            var second = future.get();
                            assertSame(first, second);
                            return first.value();
                        },
                        config)
                .withOutputType(String.class);

        var result = runner.runUntilComplete("cached");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("cached", result.getResult());
        assertEquals(1, resultDeserializations.get());
    }

    @Test
    void successfulRetryUsesTheProducingAttemptForResultSerialization() {
        var executions = new AtomicInteger();
        var resultAttempts = new ArrayList<Integer>();
        var attemptStage = identityStage((action, value, context) -> {
            if ("serialize".equals(action)
                    && context.payloadKind() == SerDesPayloadKind.RESULT
                    && "retry-step".equals(context.operationName())) {
                resultAttempts.add(context.attempt());
            }
        });
        var serDes = new JacksonSerDes()
                .then(attemptStage)
                .then(FileSystemSerDesStage.builder(basePath).build());
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var stepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                .build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> context.step(
                                "retry-step",
                                String.class,
                                stepContext -> {
                                    if (executions.incrementAndGet() == 1) {
                                        throw new IllegalStateException("retry");
                                    }
                                    return input + "-attempt-" + stepContext.getAttempt();
                                },
                                stepConfig),
                        config)
                .withOutputType(String.class);

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("value-attempt-2", result.getResult());
        assertEquals(List.of(2), resultAttempts);
    }

    @Test
    void customExceptionPayloadsRoundTripThroughFilesystem() throws Exception {
        var serDes = filesystemPipeline();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var stepConfig = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> context.step(
                                "fail-step",
                                String.class,
                                stepContext -> {
                                    throw new CustomFailure("boom");
                                },
                                stepConfig),
                        config)
                .withOutputType(String.class);

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(
                CustomFailure.class.getName(), result.getError().orElseThrow().errorType());
        var operationError = result.getOperation("fail-step").getError();
        assertEquals(CustomFailure.class.getName(), operationError.errorType());
        assertEnvelopePointsToFile(operationError.errorData());
    }

    @Test
    void nestedInvokeFailurePreservesProducerContextAcrossReplay() throws Exception {
        var childExecutions = new AtomicInteger();
        var serDes = filesystemPipeline();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            try {
                                return context.runInChildContext("invoke-child", String.class, child -> {
                                    childExecutions.incrementAndGet();
                                    return child.invoke("nested-invoke", "callee", input, String.class);
                                });
                            } catch (CustomFailure failure) {
                                return "caught:" + failure.getMessage();
                            }
                        },
                        config)
                .withOutputType(String.class);

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var errorData = new SerDesRunner(null)
                .serialize(
                        serDes,
                        new CustomFailure("invoke-boom"),
                        SerDesContext.forExecution(
                                calleeArn, "callee-invocation", "callee-execution", SerDesPayloadKind.EXCEPTION));
        runner.failChainedInvoke(
                "nested-invoke",
                ErrorObject.builder()
                        .errorType(CustomFailure.class.getName())
                        .errorMessage("invoke-boom")
                        .errorData(errorData)
                        .build());

        var completed = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, completed.getStatus());
        assertEquals("caught:invoke-boom", completed.getResult());
        assertForwardedErrorOwnedByChild(completed, "invoke-child");
        var executionsAfterCompletion = childExecutions.get();

        var replay = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
        assertEquals("caught:invoke-boom", replay.getResult());
        assertEquals(executionsAfterCompletion, childExecutions.get());
    }

    @Test
    void nestedCallbackFailurePreservesProducerContextAcrossReplay() throws Exception {
        var childExecutions = new AtomicInteger();
        var serDes = filesystemPipeline();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            try {
                                return context.runInChildContext("callback-child", String.class, child -> {
                                    childExecutions.incrementAndGet();
                                    return child.createCallback("nested-callback", String.class)
                                            .get();
                                });
                            } catch (CustomFailure failure) {
                                return "caught:" + failure.getMessage();
                            }
                        },
                        config)
                .withOutputType(String.class);

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.failCallback(
                runner.getCallbackId("nested-callback"),
                ErrorObject.builder()
                        .errorType(CustomFailure.class.getName())
                        .errorMessage("callback-boom")
                        .errorData(new JacksonSerDes().serialize(new CustomFailure("callback-boom")))
                        .build());

        var completed = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, completed.getStatus());
        assertEquals("caught:callback-boom", completed.getResult());
        assertForwardedErrorOwnedByChild(completed, "callback-child");
        var executionsAfterCompletion = childExecutions.get();

        var replay = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
        assertEquals("caught:callback-boom", replay.getResult());
        assertEquals(executionsAfterCompletion, childExecutions.get());
    }

    private SerDes filesystemPipeline() {
        var binaryStage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();
        return new JacksonSerDes()
                .then(binaryStage)
                .then(FileSystemSerDesStage.builder(basePath).build());
    }

    private static DurableExecutionInput durableInput(
            String executionArn, Operation executionOperation, List<Operation> operations, List<String> updatedIds) {
        var allOperations = new ArrayList<Operation>();
        allOperations.add(executionOperation);
        allOperations.addAll(operations);
        return new DurableExecutionInput(
                executionArn,
                "checkpoint-token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(allOperations)
                        .build(),
                updatedIds);
    }

    private static Operation executionOperation(String id, String name, String inputPayload, OperationStatus status) {
        return Operation.builder()
                .id(id)
                .name(name)
                .type(OperationType.EXECUTION)
                .status(status)
                .startTimestamp(Instant.now())
                .executionDetails(
                        ExecutionDetails.builder().inputPayload(inputPayload).build())
                .build();
    }

    private static SerDesStage identityStage(RecordingFunction recorder) {
        return new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                recorder.record("serialize", value, context);
                return value;
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                recorder.record("deserialize", data, context);
                return data;
            }
        };
    }

    private void assertEnvelopePointsToFile(String envelope) throws Exception {
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());
        assertTrue(Files.exists(file));
        assertTrue(file.startsWith(basePath));
    }

    private void assertForwardedErrorOwnedByChild(TestResult<String> result, String childName) throws Exception {
        var child = result.getOperation(childName);
        var errorData = child.getContextDetails().error().errorData();
        assertEnvelopePointsToFile(errorData);
        assertEquals(
                "operation/" + child.getId() + "/exception",
                MAPPER.readTree(errorData).get("ownerEntityId").textValue());
    }

    @FunctionalInterface
    private interface RecordingFunction {
        void record(String action, String value, SerDesContext context);
    }

    record Payload(String value) {}

    record CrossInvokeRequest(String value) {}

    record CrossInvokeResponse(String value) {}

    public static class CustomFailure extends RuntimeException {
        public CustomFailure() {}

        public CustomFailure(String message) {
            super(message);
        }
    }
}
