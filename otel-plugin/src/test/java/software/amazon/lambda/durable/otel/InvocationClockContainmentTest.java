// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;

class InvocationClockContainmentTest {
    @ParameterizedTest
    @EnumSource(InvocationStatus.class)
    void openChildAndParentShareTheConfiguredSdkClock(InvocationStatus status) {
        var wall = new AtomicLong(100_000_000_000L);
        var monotonic = new AtomicLong();
        var clock = new Clock() {
            public long now() {
                return wall.addAndGet(1_000_000);
            }

            public long nanoTime() {
                return monotonic.addAndGet(1_000);
            }
        };
        try (var exporter = InMemorySpanExporter.create()) {
            var plugin = new InvocationOtelPlugin(
                    SdkTracerProvider.builder().setClock(clock).addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                    OtelPluginConfig.builder()
                            .contextExtractor(() -> null)
                            .enableMdc(false)
                            .build());
            var start = Instant.ofEpochSecond(10);
            var arn = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/clock/execution";
            plugin.onInvocationStart(new InvocationInfo("request", arn, true, start));
            plugin.onOperationStart(new OperationInfo(
                    "parent", "parent", "CONTEXT", "WaitForCallback", null, start, null, null, false));
            plugin.onOperationStart(
                    new OperationInfo("child", "child", "CALLBACK", "Callback", "parent", start, null, null, false));
            plugin.onInvocationEnd(new InvocationEndInfo(
                    "request",
                    arn,
                    true,
                    status,
                    status == InvocationStatus.FAILED ? new IllegalStateException("failure") : null));
            var spans = exporter.getFinishedSpanItems();
            var child = spans.stream()
                    .filter(s -> s.getName().equals("child"))
                    .findFirst()
                    .orElseThrow();
            var parent = spans.stream()
                    .filter(s -> s.getName().equals("parent"))
                    .findFirst()
                    .orElseThrow();
            var invocation = spans.stream()
                    .filter(s -> s.getName().equals("Invocation"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(parent.getSpanId(), child.getParentSpanId());
            assertEquals(
                    parent.getSpanContext(),
                    child.getParentSpanContext(),
                    "Live parent keeps the cached trace ID, span ID, flags and trace state");
            assertEquals(invocation.getSpanId(), parent.getParentSpanId());
            assertTrue(spans.indexOf(child) < spans.indexOf(parent), "The plugin already drains children first");
            assertTrue(
                    child.getEndEpochNanos() <= parent.getEndEpochNanos(),
                    "Separate anchor clocks must not invert child-first end timestamps");
            assertTrue(parent.getEndEpochNanos() <= invocation.getEndEpochNanos());
            assertTrue(child.getStartEpochNanos() >= parent.getStartEpochNanos());
            assertTrue(
                    invocation.getEndEpochNanos() < 101_000_000_000L,
                    "Keep the configured clock; do not substitute system time or relax containment");
        }
    }

    @Test
    void endedParentRetainsItsCachedContextForAReplayedChildWithoutReexport() {
        try (var exporter = InMemorySpanExporter.create()) {
            var plugin = new InvocationOtelPlugin(
                    SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                    OtelPluginConfig.builder()
                            .contextExtractor(() -> null)
                            .enableMdc(false)
                            .build());
            var start = Instant.now();
            var arn = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/fallback/execution";
            plugin.onInvocationStart(new InvocationInfo("request", arn, false, start));
            plugin.onOperationStart(new OperationInfo(
                    "parent", "parent", "CONTEXT", "RunInChildContext", null, start, null, null, true));
            plugin.onOperationEnd(new OperationEndInfo(
                    "parent",
                    "parent",
                    "CONTEXT",
                    "RunInChildContext",
                    null,
                    start,
                    Instant.now(),
                    "SUCCEEDED",
                    null,
                    true,
                    null,
                    null));
            var parent = exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals("parent"))
                    .findFirst()
                    .orElseThrow();
            plugin.onOperationEnd(new OperationEndInfo(
                    "child",
                    "child",
                    "CALLBACK",
                    "Callback",
                    "parent",
                    start,
                    Instant.now(),
                    "SUCCEEDED",
                    null,
                    true,
                    null,
                    null));
            plugin.onInvocationEnd(new InvocationEndInfo("request", arn, false, InvocationStatus.PENDING, null));
            var spans = exporter.getFinishedSpanItems();
            var child = spans.stream()
                    .filter(s -> s.getName().equals("child"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(parent.getSpanContext(), child.getParentSpanContext());
            assertEquals(
                    1, spans.stream().filter(s -> s.getName().equals("parent")).count());
            assertEquals(
                    1, spans.stream().filter(s -> s.getName().equals("child")).count());
        }
    }
}
