// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.PropagationInput;
import software.amazon.lambda.durable.plugin.PropagationMetadata;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.testing.local.OperationResult;

/** Real public invoke/checkpoint/replay paths; requires the generated XAmznTraceId model member. */
class InvokePropagationIntegrationTest {
    private static final String TRACE = "6955b900123456789012345678901234";
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:parent/durable-execution/test/execution-id";
    private static final String HEADER = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1";
    private static final String PAYLOAD = "{\"traceparent\":\"customer-owned\",\"value\":42}";
    private static final BiFunction<String, DurableContext, String> INVOKE =
            (input, context) -> context.invoke("invoke", "target:live", Map.of("value", 42), String.class);

    @Test
    void committedStartAndTerminalReplayNeverRecollectOrReserialize() {
        var collected = new CopyOnWriteArrayList<PropagationInput>();
        var payloadCalls = new AtomicInteger();
        var plugin = producer(collected);
        var invokeConfig = InvokeConfig.builder()
                .tenantId("tenant-A")
                .payloadSerDes(new JacksonSerDes() {
                    @Override
                    public String serialize(Object value, SerDesContext context) {
                        payloadCalls.incrementAndGet();
                        return PAYLOAD;
                    }
                })
                .build();
        BiFunction<String, DurableContext, String> handler = (input, context) ->
                context.invoke("invoke", "target:live", Map.of("value", 42), String.class, invokeConfig);
        try (var fixture = new Fixture(plugin)) {
            fixture.client.beforeInvokeCheckpoint = update -> {
                assertEquals(1, collected.size(), "Metadata must be collected before checkpoint transport");
                assertEquals(1, payloadCalls.get());
                assertEquals(HEADER, update.chainedInvokeOptions().xAmznTraceId());
                assertEquals(PAYLOAD, update.payload());
            };
            assertEquals(ExecutionStatus.PENDING, fixture.run(handler).status());
            assertEquals(ExecutionStatus.PENDING, fixture.run(handler).status());
            fixture.client.completeChainedInvoke("invoke", OperationResult.succeeded("\"child-result\""));
            assertEquals("\"child-result\"", fixture.run(handler).result());
            assertEquals("\"child-result\"", fixture.run(handler).result());
            assertEquals(1, collected.size());
            assertEquals(1, payloadCalls.get());
            var update = fixture.client.starts().get(0);
            assertEquals(1, fixture.client.starts().size());
            assertEquals(HEADER, update.chainedInvokeOptions().xAmznTraceId());
            assertEquals("target:live", update.chainedInvokeOptions().functionName());
            assertEquals("tenant-A", update.chainedInvokeOptions().tenantId());
            assertEquals(PAYLOAD, update.payload());
            assertEquals("invoke", update.name());
            assertEquals(OperationType.CHAINED_INVOKE, update.type());
            assertEquals(collected.get(0).operationId(), update.id());
            assertEquals(ARN, collected.get(0).executionArn());
            assertEquals("target:live", collected.get(0).targetFunctionName());
            assertNull(collected.get(0).parentOperationId());
        }
    }

    @Test
    void payloadSerializationFailureDoesNotCollectOrSendStart() {
        var collected = new CopyOnWriteArrayList<PropagationInput>();
        var invokeConfig = InvokeConfig.builder()
                .payloadSerDes(new JacksonSerDes() {
                    @Override
                    public String serialize(Object value, SerDesContext context) {
                        throw new IllegalStateException("payload serialization failed");
                    }
                })
                .build();
        try (var fixture = new Fixture(producer(collected))) {
            var result = fixture.run(
                    (input, context) -> context.invoke("invoke", "target:live", "payload", String.class, invokeConfig));
            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals("payload serialization failed", result.error().errorMessage());
            assertTrue(collected.isEmpty());
            assertTrue(fixture.client.starts().isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = OperationStatus.class,
            names = {"FAILED", "TIMED_OUT", "STOPPED"})
    void terminalFailureReplayPreservesOutcomeWithoutCollecting(OperationStatus status) {
        var collected = new CopyOnWriteArrayList<PropagationInput>();
        try (var fixture = new Fixture(producer(collected))) {
            assertEquals(ExecutionStatus.PENDING, fixture.run(INVOKE).status());
            fixture.client.completeChainedInvoke(
                    "invoke",
                    new OperationResult(
                            status,
                            null,
                            ErrorObject.builder()
                                    .errorType("child-error")
                                    .errorMessage("child failed")
                                    .build()));
            var first = fixture.run(INVOKE);
            var replay = fixture.run(INVOKE);
            assertEquals(ExecutionStatus.FAILED, first.status());
            assertEquals(first.status(), replay.status());
            assertEquals(first.error(), replay.error());
            assertEquals(1, collected.size());
            assertEquals(1, fixture.client.starts().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "legacy", "null", "empty", "blank", "exception", "cancellation"})
    void absentOrOrdinarilyFailingPluginPreservesUninstrumentedInvoke(String mode) {
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                return switch (mode) {
                    case "empty" -> new PropagationMetadata(null);
                    case "blank" -> new PropagationMetadata(" ");
                    case "exception" -> throw new IllegalStateException("optional plugin failure");
                    case "cancellation" -> throw new CancellationException("plugin cancellation");
                    default -> null;
                };
            }
        };
        var plugins = mode.equals("none")
                ? new DurableExecutionPlugin[0]
                : new DurableExecutionPlugin[] {mode.equals("legacy") ? new DurableExecutionPlugin() {} : plugin};
        try (var fixture = new Fixture(plugins)) {
            fixture.client.autoComplete = true;
            assertEquals(ExecutionStatus.SUCCEEDED, fixture.run(INVOKE).status());
            var options = fixture.client.starts().get(0).chainedInvokeOptions();
            assertNull(options.xAmznTraceId());
            assertEquals("target:live", options.functionName());
        }
    }

    @Test
    void failedUncommittedStartCanCollectAgainWithTheSameOperationIdentity() {
        var collected = new CopyOnWriteArrayList<PropagationInput>();
        try (var fixture = new Fixture(producer(collected))) {
            fixture.client.failNextStart.set(true);
            var error = assertThrows(UnrecoverableDurableExecutionException.class, () -> fixture.run(INVOKE));
            assertTrue(error.isRetryable());
            assertTrue(fixture.client.getAllOperations().isEmpty(), "Failed checkpoint was not committed");
            assertEquals(ExecutionStatus.PENDING, fixture.run(INVOKE).status());
            assertEquals(2, collected.size(), "Uncommitted attempts do not promise exactly-once hook calls");
            assertEquals(collected.get(0).operationId(), collected.get(1).operationId());
            assertEquals(collected.get(0).executionArn(), collected.get(1).executionArn());
            assertEquals(2, fixture.client.starts().size());
            assertEquals(fixture.client.starts().get(0), fixture.client.starts().get(1));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    @Timeout(20)
    void batchedPublicInvokesCarryTheirOwnActualOperationSpan(boolean executionView, boolean sampled) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setSampler(sampled ? Sampler.alwaysOff() : Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        TRACE,
                        "1234567890123456",
                        sampled ? ExtractedContext.Sampling.SAMPLED : ExtractedContext.Sampling.NOT_SAMPLED))
                .build();
        DurableExecutionPlugin plugin =
                executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
        var bothInvokes = new CountDownLatch(2);
        var inputs = new CopyOnWriteArrayList<PropagationInput>();
        var startedOperations = new CopyOnWriteArrayList<OperationInfo>();
        DurableExecutionPlugin observer = new DurableExecutionPlugin() {
            @Override
            public void onOperationStart(OperationInfo info) {
                startedOperations.add(info);
            }

            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                inputs.add(input);
                bothInvokes.countDown();
                try {
                    assertTrue(bothInvokes.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return null;
            }
        };
        try (var fixture = new Fixture(plugin, observer)) {
            fixture.client.autoComplete = true;
            var result = fixture.run((input, context) -> {
                try (var parallel = context.parallel(
                        "fanout", ParallelConfig.builder().maxConcurrency(2).build())) {
                    parallel.branch(
                            "a",
                            String.class,
                            child -> child.invoke("invoke-a", "target-a:live", "payload-a", String.class));
                    parallel.branch(
                            "b",
                            String.class,
                            child -> child.invoke("invoke-b", "target-b:live", "payload-b", String.class));
                }
                return "done";
            });
            assertEquals(ExecutionStatus.SUCCEEDED, result.status());
            assertEquals(2, inputs.size());
            var starts = fixture.client.starts();
            assertEquals(2, starts.size());
            assertTrue(fixture.client.batches.stream()
                    .anyMatch(batch -> batch.stream()
                                    .filter(InvokePropagationIntegrationTest::isInvokeStart)
                                    .count()
                            == 2));
            assertNotEquals(starts.get(0).id(), starts.get(1).id());
            assertNotEquals(
                    starts.get(0).chainedInvokeOptions().xAmznTraceId(),
                    starts.get(1).chainedInvokeOptions().xAmznTraceId());
            for (var update : starts) {
                var metadata = inputs.stream()
                        .filter(item -> item.operationId().equals(update.id()))
                        .findFirst()
                        .orElseThrow();
                assertEquals(update.parentId(), metadata.parentOperationId());
                assertNotNull(metadata.parentOperationId());
                assertEquals(update.chainedInvokeOptions().functionName(), metadata.targetFunctionName());
                var spanId = new DeterministicIdGenerator().generateSpanIdForOperation(ARN, update.id());
                assertEquals(
                        "Root=1-6955b900-123456789012345678901234;Parent=" + spanId + ";Sampled="
                                + (sampled ? "1" : "0"),
                        update.chainedInvokeOptions().xAmznTraceId());
                if (sampled) {
                    var span = exporter.getFinishedSpanItems().stream()
                            .filter(item -> update.id()
                                            .equals(item.getAttributes()
                                                    .get(AttributeKey.stringKey("durable.operation.id")))
                                    && update.name().equals(item.getName()))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(span.getSpanId(), spanId);
                    assertEquals(TRACE, span.getTraceId());
                }
            }
            assertEquals(
                    sampled ? startedOperations.size() + 2 : 0,
                    exporter.getFinishedSpanItems().size(),
                    "No extra span is created to obtain propagation metadata");
        }
    }

    private static DurableExecutionPlugin producer(List<PropagationInput> inputs) {
        return new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                inputs.add(input);
                return new PropagationMetadata(HEADER);
            }
        };
    }

    private static boolean isInvokeStart(OperationUpdate update) {
        return update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START;
    }

    private static final class RecordingClient extends LocalMemoryExecutionClient {
        final List<List<OperationUpdate>> batches = new CopyOnWriteArrayList<>();
        final AtomicBoolean failNextStart = new AtomicBoolean();
        boolean autoComplete;
        Consumer<OperationUpdate> beforeInvokeCheckpoint = update -> {};

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            batches.add(List.copyOf(updates));
            var starts = updates.stream()
                    .filter(InvokePropagationIntegrationTest::isInvokeStart)
                    .toList();
            starts.forEach(beforeInvokeCheckpoint);
            if (!starts.isEmpty() && failNextStart.compareAndSet(true, false)) {
                throw new UnrecoverableDurableExecutionException(
                        ErrorObject.builder()
                                .errorType("CheckpointUnavailable")
                                .errorMessage("simulated uncommitted START")
                                .build(),
                        true);
            }
            var response = super.checkpoint(arn, token, updates);
            if (autoComplete && !starts.isEmpty()) {
                starts.forEach(
                        update -> completeChainedInvoke(update.name(), OperationResult.succeeded("\"completed\"")));
                // Consume the simulator's newly completed results once. Returning all stored operations leaves
                // these updates pending and spuriously delivers the same terminal event in the next checkpoint.
                var completed = super.checkpoint(arn, response.checkpointToken(), List.of());
                var changed = new LinkedHashMap<String, Operation>();
                response.newExecutionState().operations().forEach(operation -> changed.put(operation.id(), operation));
                completed.newExecutionState().operations().forEach(operation -> changed.put(operation.id(), operation));
                return completed.toBuilder()
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(changed.values())
                                .build())
                        .build();
            }
            return response;
        }

        List<OperationUpdate> starts() {
            return batches.stream()
                    .flatMap(List::stream)
                    .filter(InvokePropagationIntegrationTest::isInvokeStart)
                    .toList();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingClient client = new RecordingClient();
        final ExecutorService executor = Executors.newCachedThreadPool();
        final DurableConfig config;

        Fixture(DurableExecutionPlugin... plugins) {
            config = DurableConfig.builder()
                    .withDurableExecutionClient(client)
                    .withExecutorService(executor)
                    .withCheckpointDelay(Duration.ofMillis(250))
                    .withPlugins(plugins)
                    .build();
        }

        DurableExecutionOutput run(BiFunction<String, DurableContext, String> handler) {
            var operations = new ArrayList<>(List.of(Operation.builder()
                    .id("execution-id")
                    .type(OperationType.EXECUTION)
                    .status(OperationStatus.STARTED)
                    .startTimestamp(Instant.parse("2026-10-05T00:00:00Z"))
                    .executionDetails(
                            ExecutionDetails.builder().inputPayload("\"input\"").build())
                    .build()));
            operations.addAll(client.getAllOperations());
            var input = new DurableExecutionInput(
                    ARN,
                    "token",
                    CheckpointUpdatedExecutionState.builder()
                            .operations(operations)
                            .build(),
                    client.getUpdatedOperationIdsSinceLastInvocation());
            return DurableExecutor.execute(input, null, TypeToken.get(String.class), handler, config);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
