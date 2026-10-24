// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_INVOCATION_STATUS;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.serde.JacksonSerDes;

@Timeout(20)
class SerializationCancellationOtelIntegrationTest {
    @BeforeEach
    @AfterEach
    void clearState() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationDuringSmallResultSerializationDoesNotCompleteWorkflow(boolean executionView) throws Exception {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var pluginConfig = OtelPluginConfig.builder()
                .contextExtractor(() -> null)
                .enableMdc(false)
                .build();
        var factory = executionView
                ? ExecutionOtelPlugin.factory(provider, pluginConfig)
                : InvocationOtelPlugin.factory(provider, pluginConfig);
        var serializationEntered = new CountDownLatch(1);
        var serializationInterrupted = new CountDownLatch(1);
        var releaseSerialization = new CountDownLatch(1);
        var caller = new AtomicReference<Thread>();
        var callerInterrupted = new AtomicBoolean();
        var workers = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var serializer = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if ("result".equals(value)) {
                    serializationEntered.countDown();
                    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (true) {
                        try {
                            assertTrue(releaseSerialization.await(
                                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                            break;
                        } catch (InterruptedException ignored) {
                            serializationInterrupted.countDown();
                        }
                    }
                }
                return super.serialize(value);
            }
        };
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .withSerDes(serializer)
                .withPlugins(factory)
                .build();
        var runtime = mock(Context.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(10_000);
        when(runtime.getAwsRequestId()).thenReturn("serialization-cancellation");
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/otel/exec",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("exec")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.now())
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
        try {
            var response = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input, runtime, TypeToken.get(String.class), (value, context) -> "result", config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            assertTrue(serializationEntered.await(5, TimeUnit.SECONDS));
            caller.get().interrupt();
            assertTrue(serializationInterrupted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> response.get(100, TimeUnit.MILLISECONDS));
            releaseSerialization.countDown();
            var failure = assertInstanceOf(
                    UnrecoverableDurableExecutionException.class,
                    assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertTrue(failure.isRetryable());
            assertTrue(callerInterrupted.get());
            var spans = exporter.getFinishedSpanItems();
            var invocations = spans.stream()
                    .filter(span -> span.getName().equals("Invocation"))
                    .toList();
            assertEquals(1, invocations.size(), "The real plugin must still export the completed invocation");
            assertAll(
                    () -> assertEquals(
                            "RETRYING", invocations.get(0).getAttributes().get(DURABLE_INVOCATION_STATUS)),
                    () -> assertTrue(
                            spans.stream().noneMatch(span -> span.getName().equals("Workflow")),
                            "A cancelled preparation must not emit a terminal successful Workflow span"));
        } finally {
            releaseSerialization.countDown();
            workers.shutdownNow();
            callers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            exporter.close();
        }
    }
}
