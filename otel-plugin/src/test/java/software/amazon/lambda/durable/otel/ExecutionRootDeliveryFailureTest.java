// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.util.ExceptionHelper;

class ExecutionRootDeliveryFailureTest {
    @ParameterizedTest
    @CsvSource({
        "false,serialize",
        "true,serialize",
        "false,checkpoint",
        "true,checkpoint",
        "false,root-end",
        "true,root-end",
        "false,success",
        "true,success"
    })
    void outputPreparationAndOrdinaryRootEndFailuresStillReleaseAndFlush(boolean executionView, String mode)
            throws Exception {
        var originalContext = Context.current();
        var failure = new IllegalStateException("controlled " + mode);
        var failOnce = new AtomicBoolean(true);
        var bodyCalls = new AtomicInteger();
        var rootEnds = new AtomicInteger();
        var flushes = new AtomicInteger();
        var roots = new CopyOnWriteArrayList<ReadWriteSpan>();
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var observer = new SpanProcessor() {
            public void onStart(Context parent, ReadWriteSpan span) {
                if (span.getName().equals("DurableExecutionRoot")) roots.add(span);
            }

            public boolean isStartRequired() {
                return true;
            }

            public void onEnd(ReadableSpan span) {
                if (span.getName().equals("DurableExecutionRoot")) {
                    rootEnds.incrementAndGet();
                    if (mode.equals("root-end") && failOnce.compareAndSet(true, false)) throw failure;
                }
            }

            public boolean isEndRequired() {
                return true;
            }

            public CompletableResultCode forceFlush() {
                flushes.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
        };
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (mode.equals("checkpoint")
                        && updates.stream().anyMatch(u -> u.type() == OperationType.EXECUTION)
                        && failOnce.compareAndSet(true, false)) throw failure;
                return super.checkpoint(arn, token, updates);
            }
        };
        var serDes = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                if (mode.equals("serialize") && "result".equals(value) && failOnce.compareAndSet(true, false))
                    throw failure;
                return delegate.serialize(value);
            }

            public <T> T deserialize(String data, TypeToken<T> type) {
                return delegate.deserialize(data, type);
            }
        };
        var caller = Executors.newSingleThreadExecutor();
        var workers = Executors.newCachedThreadPool();
        try (var exporter = InMemorySpanExporter.create();
                var batch = BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofDays(1))
                        .build()) {
            var builder = SdkTracerProvider.builder().addSpanProcessor(observer).addSpanProcessor(batch);
            var otelConfig = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            DurableExecutionPlugin otel = executionView
                    ? new ExecutionOtelPlugin(builder, otelConfig)
                    : new InvocationOtelPlugin(builder, otelConfig);
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(client)
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withSerDes(serDes)
                    .withPlugins(otel, new DurableExecutionPlugin() {
                        public void onInvocationEnd(InvocationEndInfo info) {
                            ends.add(info);
                        }
                    })
                    .build();
            try {
                var first = caller.submit(() -> DurableExecutor.execute(
                        input(),
                        null,
                        TypeToken.get(String.class),
                        (value, context) -> bodyCalls.incrementAndGet() == 1 && mode.equals("checkpoint")
                                ? "x".repeat(6 * 1024 * 1024)
                                : "result",
                        config));
                if (mode.equals("serialize") || mode.equals("checkpoint")) {
                    var thrown = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
                    assertSame(failure, thrown.getCause(), "Output preparation failure remains caller-visible");
                } else
                    assertEquals(
                            ExecutionStatus.SUCCEEDED,
                            first.get(5, TimeUnit.SECONDS).status());
                var exported = exporter.getFinishedSpanItems();
                System.out.println("ROOT_DELIVERY view=" + executionView + " mode=" + mode + " ends=" + ends.size()
                        + " rootEnds=" + rootEnds.get() + " rootRecording="
                        + roots.get(0).isRecording()
                        + " flushes=" + flushes.get() + " exported="
                        + exported.stream().map(s -> s.getName()).toList());
                assertEquals(1, ends.size(), "Output preparation failure still has exactly one End");
                assertEquals(
                        mode.equals("serialize") || mode.equals("checkpoint")
                                ? InvocationStatus.RETRYING
                                : InvocationStatus.SUCCEEDED,
                        ends.get(0).invocationStatus());
                assertEquals(1, rootEnds.get());
                assertFalse(roots.get(0).isRecording());
                assertEquals(1, flushes.get(), "Root onEnd failure does not skip flushing completed spans");
                assertTrue(
                        exported.stream().anyMatch(s -> s.getName().equals("Invocation")),
                        "The BatchSpanProcessor must be flushed before invocation return");
                var second = caller.submit(() -> DurableExecutor.execute(
                        input(), null, TypeToken.get(String.class), (value, context) -> "result", config));
                assertEquals(
                        ExecutionStatus.SUCCEEDED,
                        second.get(5, TimeUnit.SECONDS).status());
                assertEquals(2, ends.size());
                assertEquals(2, rootEnds.get());
                assertEquals(2, flushes.get());
                assertEquals(roots.get(0).getSpanContext(), roots.get(1).getSpanContext());
                assertTrue(roots.stream().noneMatch(ReadWriteSpan::isRecording));
                assertSame(originalContext, Context.current());
            } finally {
                caller.shutdownNow();
                workers.shutdownNow();
                assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.ofEpochSecond(10))
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/root/execution",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    static Stream<Arguments> preparationEndFailures() {
        return Stream.of(false, true)
                .flatMap(view -> Stream.of("ordinary", "vm", "death", "wrapped-vm")
                        .flatMap(preparation -> Stream.of("assertion", "vm", "death")
                                .map(end -> Arguments.of(view, preparation, end))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("preparationEndFailures")
    void preparationAndEndFailuresRetainCallerIdentityAndCleanupDiagnostics(
            boolean executionView, String preparationKind, String endKind) {
        Throwable original =
                switch (preparationKind) {
                    case "vm", "wrapped-vm" -> new InternalError("preparation fatal");
                    case "death" -> new ThreadDeath();
                    default -> new IllegalStateException("preparation ordinary");
                };
        Throwable preparation = preparationKind.equals("wrapped-vm") ? new CompletionException(original) : original;
        Error cleanup =
                switch (endKind) {
                    case "vm" -> new InternalError("End fatal");
                    case "death" -> new ThreadDeath();
                    default -> new AssertionError("End error");
                };
        var ends = new AtomicInteger();
        var seen = new AtomicReference<InvocationEndInfo>();
        var serDes = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                if ("result".equals(value)) ExceptionHelper.sneakyThrow(preparation);
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                return delegate.deserialize(value, type);
            }
        };
        try (var exporter = InMemorySpanExporter.create()) {
            var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var otelConfig = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            DurableExecutionPlugin otel = executionView
                    ? new ExecutionOtelPlugin(builder, otelConfig)
                    : new InvocationOtelPlugin(builder, otelConfig);
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (value, context) -> "result",
                    DurableConfig.builder()
                            .withSerDes(serDes)
                            .withPlugins(otel, new DurableExecutionPlugin() {
                                public void onInvocationEnd(InvocationEndInfo info) {
                                    ends.incrementAndGet();
                                    seen.set(info);
                                    throw cleanup;
                                }
                            })
                            .build());
            Throwable expected = original instanceof VirtualMachineError || original instanceof ThreadDeath
                    ? original
                    : cleanup instanceof VirtualMachineError || cleanup instanceof ThreadDeath ? cleanup : original;
            assertSame(expected, assertThrows(Throwable.class, () -> runner.run("input")));
            assertEquals(List.of(expected == original ? cleanup : original), List.of(expected.getSuppressed()));
            assertEquals(1, ends.get());
            assertEquals(InvocationStatus.RETRYING, seen.get().invocationStatus());
            assertSame(original, seen.get().executionError());
            var roots = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("DurableExecutionRoot"))
                    .toList();
            assertEquals(1, roots.size());
            assertEquals(roots.get(0).getStartEpochNanos(), roots.get(0).getEndEpochNanos());
        }
    }
}
