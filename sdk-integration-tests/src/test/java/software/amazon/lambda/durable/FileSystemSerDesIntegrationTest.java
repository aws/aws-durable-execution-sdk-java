// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
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
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.testing.local.OperationResult;

class FileSystemSerDesIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void storesStepAndExecutionResultsAcrossReplay() throws Exception {
        var stepRuns = new AtomicInteger();
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var result = context.step("persist", String.class, stepContext -> {
                        stepRuns.incrementAndGet();
                        return input + "-stored";
                    });
                    context.wait("replay", Duration.ofSeconds(1));
                    return result;
                },
                config);

        var result = runner.runUntilComplete("value");

        assertEquals("value-stored", result.getResult(String.class));
        assertEquals("value-stored", result.getOperation("persist").getStepResult(String.class));
        assertEquals(1, stepRuns.get());
        try (var files = Files.walk(tempDir)) {
            assertTrue(files.filter(Files::isRegularFile).count() >= 2);
        }
    }

    @Test
    void operationConfigControlsWhereFilesystemStorageIsUsed() throws Exception {
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(new JacksonSerDes()).build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> context.step(
                                "filesystem-step",
                                String.class,
                                stepContext -> input + "-stored",
                                StepConfig.builder().serDes(fileSystemSerDes).build()),
                        config)
                .withOperationSerDesResolver((operation, defaultSerDes) ->
                        "filesystem-step".equals(operation.name()) ? fileSystemSerDes : defaultSerDes);

        var result = runner.runUntilComplete("value");

        assertEquals("value-stored", result.getResult(String.class));
        assertEquals("value-stored", result.getOperation("filesystem-step").getStepResult(String.class));
        try (var files = Files.walk(tempDir)) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void durableExecutorAcceptsRawServiceInputBeforeFilesystemEnvelopeExists() {
        var executionArn =
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/execution/raw-input";
        var executionOperation =
                executionOperation("raw-input", "execution", "\"service-input\"", OperationStatus.STARTED);
        var client = new LocalMemoryExecutionClient();
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withSerDes(serDes)
                .build();

        var output = DurableExecutor.execute(
                durableInput(executionArn, executionOperation, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                (value, context) -> value + "-output",
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("service-input-output", serDes.deserialize(output.result(), TypeToken.get(String.class)));
    }

    @Test
    void callerAndCalleeExchangeOffloadedInvokePayloadAndResult() throws Exception {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var callerClient = new LocalMemoryExecutionClient();
        var callerConfig = DurableConfig.builder()
                .withDurableExecutionClient(callerClient)
                .withSerDes(serDes)
                .build();
        BiFunction<String, DurableContext, CrossInvokeResponse> callerHandler = (input, context) ->
                context.invoke("call-callee", "callee", new CrossInvokeRequest(input), CrossInvokeResponse.class);
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
        assertEnvelopePointsToFile(invokePayload);

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
        assertEquals(
                new CrossInvokeResponse("reply:request"),
                serDes.deserialize(resumed.result(), TypeToken.get(CrossInvokeResponse.class)));
    }

    @Test
    void invokePayloadOverridePreservesStandardJsonWireContract() {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var callerClient = new LocalMemoryExecutionClient();
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var callerConfig = DurableConfig.builder()
                .withDurableExecutionClient(callerClient)
                .withSerDes(fileSystemSerDes)
                .build();
        BiFunction<String, DurableContext, String> handler = (input, context) -> context.invoke(
                "call-standard",
                "standard",
                new CrossInvokeRequest(input),
                String.class,
                InvokeConfig.builder().payloadSerDes(new JacksonSerDes()).build());
        var execution =
                executionOperation("caller-invocation", "caller-execution", "\"request\"", OperationStatus.STARTED);

        var pending = DurableExecutor.execute(
                durableInput(callerArn, execution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                handler,
                callerConfig);

        assertEquals(ExecutionStatus.PENDING, pending.status());
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();
        assertEquals("{\"value\":\"request\"}", invokePayload);
    }

    @Test
    void replaysStepWaitForConditionChildAndMapPayloads() throws Exception {
        var stepRuns = new AtomicInteger();
        var pollRuns = new AtomicInteger();
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var stepResult = context.step("load-order", String.class, stepContext -> {
                        stepRuns.incrementAndGet();
                        return input + "-loaded";
                    });
                    var pollResult = context.waitForCondition(
                            "poll-order",
                            Integer.class,
                            (state, stepContext) -> {
                                pollRuns.incrementAndGet();
                                var next = state == null ? 1 : state + 1;
                                return next == 2
                                        ? WaitForConditionResult.stopPolling(next)
                                        : WaitForConditionResult.continuePolling(next);
                            },
                            WaitForConditionConfig.<Integer>builder()
                                    .waitStrategy(WaitStrategies.exponentialBackoff(
                                            5, Duration.ofSeconds(1), Duration.ofSeconds(10), 1, JitterStrategy.NONE))
                                    .build());
                    var childResult =
                            context.runInChildContext("format-order", String.class, child -> stepResult + "-child");
                    var mapResult =
                            context.map("map-order", List.of(1, 2), Integer.class, (item, index, child) -> item * 2);
                    return childResult + "-" + pollResult + "-" + mapResult.results();
                },
                config);

        var result = runner.runUntilComplete("order");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("order-loaded-child-2-[2, 4]", result.getResult(String.class));
        assertEquals(1, stepRuns.get());
        assertEquals(2, pollRuns.get());
        assertEquals("order-loaded", result.getOperation("load-order").getStepResult(String.class));
        assertEnvelopePointsToFile(
                result.getOperation("load-order").getStepDetails().result());
        assertEnvelopePointsToFile(
                result.getOperation("map-order").getContextDetails().result());
    }

    @Test
    void acceptsRawCallbackAndInvokeResultsWithBoundarySpecificSerDes() {
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(fileSystemSerDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var approval =
                            context.createCallback("approval", String.class).get();
                    return context.invoke(
                            "notify",
                            "target-function",
                            approval,
                            String.class,
                            InvokeConfig.builder()
                                    .payloadSerDes(new JacksonSerDes())
                                    .serDes(fileSystemSerDes)
                                    .build());
                },
                config);

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.completeCallback(runner.getCallbackId("approval"), "\"approved\"");
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.completeChainedInvoke("notify", "\"notified\"");

        var completed = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, completed.getStatus());
        assertEquals("notified", completed.getResult(String.class));
    }

    @Test
    void repeatedGetUsesInvocationDeserializationCache() {
        var resultDeserializations = new AtomicInteger();
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var countingSerDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return fileSystemSerDes.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                if (typeToken.equals(TypeToken.get(Payload.class)) && SerDesContext.getCurrentContext() != null) {
                    resultDeserializations.incrementAndGet();
                }
                return fileSystemSerDes.deserialize(data, typeToken);
            }
        };
        var config = DurableConfig.builder().withSerDes(countingSerDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var future = context.stepAsync("cached-step", Payload.class, stepContext -> new Payload(input));
                    var first = future.get();
                    var second = future.get();
                    assertSame(first, second);
                    return first.value();
                },
                config);

        var result = runner.runUntilComplete("cached");

        assertEquals("cached", result.getResult(String.class));
        assertEquals(1, resultDeserializations.get());
    }

    @Test
    void customExceptionPayloadRoundTripsThroughFilesystem() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "fail-step",
                        String.class,
                        stepContext -> {
                            throw new CustomFailure("boom");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                config);

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var operationError = result.getOperation("fail-step").getError();
        assertEquals(CustomFailure.class.getName(), operationError.errorType());
        assertEnvelopePointsToFile(operationError.errorData());
    }

    @Test
    void payloadKindEntityIdsPreserveDeterministicExternalStateAcrossReplay() {
        var attempts = new AtomicInteger();
        var serDes = new DeterministicExternalSerDes();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> context.waitForCondition(
                                "poll",
                                String.class,
                                (state, stepContext) -> {
                                    if (attempts.incrementAndGet() == 1) {
                                        return WaitForConditionResult.continuePolling("checkpoint-state");
                                    }
                                    throw new IllegalStateException("poll failed");
                                },
                                WaitForConditionConfig.<String>builder()
                                        .waitStrategy(WaitStrategies.exponentialBackoff(
                                                3,
                                                Duration.ofSeconds(1),
                                                Duration.ofSeconds(10),
                                                1,
                                                JitterStrategy.NONE))
                                        .build()),
                        config)
                .withOutputType(String.class);

        var pending = runner.run("input");
        var stateReference = pending.getOperation("poll").getStepDetails().result();

        assertEquals(ExecutionStatus.PENDING, pending.getStatus());
        runner.advanceTime();

        var failed = runner.run("input");

        assertEquals(ExecutionStatus.FAILED, failed.getStatus());
        assertEquals("checkpoint-state", serDes.deserialize(stateReference, TypeToken.get(String.class)));
        assertTrue(serDes.keys().stream().anyMatch(key -> key.endsWith("/result")));
        assertTrue(serDes.keys().stream().anyMatch(key -> key.endsWith("/exception")));
    }

    private void assertEnvelopePointsToFile(String envelope) throws Exception {
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());
        assertTrue(Files.exists(file));
        assertTrue(file.startsWith(tempDir));
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

    record Payload(String value) {}

    record CrossInvokeRequest(String value) {}

    record CrossInvokeResponse(String value) {}

    private static final class DeterministicExternalSerDes implements SerDes {
        private static final String REFERENCE_PREFIX = "external:";
        private final JacksonSerDes delegate = new JacksonSerDes();
        private final ConcurrentHashMap<String, String> storage = new ConcurrentHashMap<>();

        @Override
        public String serialize(Object value) {
            var context = SerDesContext.getCurrentContext();
            if (context == null) {
                return delegate.serialize(value);
            }
            var key = context.durableExecutionArn() + "#" + context.entityId();
            storage.put(key, delegate.serialize(value));
            return REFERENCE_PREFIX + key;
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            var serialized = data;
            if (data != null && data.startsWith(REFERENCE_PREFIX)) {
                var key = data.substring(REFERENCE_PREFIX.length());
                serialized = storage.get(key);
                if (serialized == null) {
                    throw new IllegalStateException("Missing external value: " + key);
                }
            }
            return delegate.deserialize(serialized, typeToken);
        }

        Set<String> keys() {
            return Set.copyOf(storage.keySet());
        }
    }

    public static class CustomFailure extends RuntimeException {
        public CustomFailure() {}

        public CustomFailure(String message) {
            super(message);
        }
    }
}
