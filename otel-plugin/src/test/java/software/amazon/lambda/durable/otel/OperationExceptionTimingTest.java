// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_OPERATION_NAME;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;

class OperationExceptionTimingTest {
    static Stream<Arguments> paths() {
        return Stream.of(false, true)
                .flatMap(execution -> Stream.of(
                                "live", "continuation", "missing-start", "late-start", "no-end", "zero-end")
                        .map(path -> Arguments.of(execution, path)));
    }

    @ParameterizedTest
    @MethodSource("paths")
    void exceptionUsesOperationEndWithoutChangingViewClockPolicy(boolean executionView, String path) {
        var nano = new AtomicLong();
        var clock = new Clock() {
            public long now() {
                return 100_000_000_000L;
            }

            public long nanoTime() {
                return nano.addAndGet(1_000);
            }
        };
        try (var exporter = InMemorySpanExporter.create()) {
            var builder =
                    SdkTracerProvider.builder().setClock(clock).addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var settings = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            DurableExecutionPluginFactory factory = executionView
                    ? ExecutionOtelPlugin.factory(builder, settings)
                    : InvocationOtelPlugin.factory(builder, settings);
            var start = path.equals("missing-start") || path.equals("no-end") || path.equals("zero-end")
                    ? null
                    : Instant.ofEpochSecond(path.equals("late-start") ? 30 : 10);
            var end =
                    path.equals("no-end") ? null : path.equals("zero-end") ? Instant.EPOCH : Instant.ofEpochSecond(20);
            var plugin = Invocations.started(
                    factory, new InvocationInfo("request", "arn:timing", true, Instant.ofEpochSecond(1)));
            if (path.equals("live") || path.equals("late-start"))
                plugin.onOperationStart(new OperationInfo(
                        "operation", "timing-target", "CALLBACK", "Callback", null, start, null, null, false));
            plugin.onOperationEnd(new OperationEndInfo(
                    "operation",
                    "timing-target",
                    "CALLBACK",
                    "Callback",
                    null,
                    start,
                    end,
                    "FAILED",
                    null,
                    path.equals("continuation"),
                    new IllegalStateException("backend failure"),
                    null));
            plugin.onInvocationEnd(
                    new InvocationEndInfo("request", "arn:timing", true, InvocationStatus.SUCCEEDED, null));
            var span = exporter.getFinishedSpanItems().stream()
                    .filter(s -> "timing-target".equals(s.getAttributes().get(DURABLE_OPERATION_NAME)))
                    .findFirst()
                    .orElseThrow();
            assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
            assertEquals(1, span.getEvents().size());
            long event = span.getEvents().get(0).getEpochNanos();
            System.out.printf(
                    "OPERATION_TIME execution=%s path=%s start=%s end=%s event=%s%n",
                    executionView, path, span.getStartEpochNanos(), span.getEndEpochNanos(), event);
            if (executionView && end != null && !end.equals(Instant.EPOCH)) {
                assertEquals(20_000_000_000L, span.getEndEpochNanos(), "retain the known backend end");
                long expectedStart = start == null ? span.getEndEpochNanos() : start.getEpochSecond() * 1_000_000_000L;
                assertEquals(expectedStart, span.getStartEpochNanos(), "only a missing start falls back to known end");
                assertEquals(span.getEndEpochNanos(), event, "exception belongs at the same actual operation end");
                if (!path.equals("late-start")) assertTrue(event >= span.getStartEpochNanos());
                else
                    assertTrue(
                            span.getStartEpochNanos() > span.getEndEpochNanos(),
                            "do not introduce clock-skew clamping");
            } else {
                assertTrue(span.getStartEpochNanos() >= 100_000_000_000L);
                assertTrue(span.getEndEpochNanos() < 101_000_000_000L, "retain the configured SDK clock");
                assertTrue(event >= span.getStartEpochNanos() && event <= span.getEndEpochNanos());
            }
        }
    }
}
