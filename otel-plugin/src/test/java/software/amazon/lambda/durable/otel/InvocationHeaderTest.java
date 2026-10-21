// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class InvocationHeaderTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlappingInvocationsAndResumeKeepTheirOwnHeader(boolean executionView) throws Exception {
        var barrier = new CyclicBarrier(2);
        var sampled = CompletableFuture.runAsync(() -> runInvocations(executionView, true, barrier));
        var unsampled = CompletableFuture.runAsync(() -> runInvocations(executionView, false, barrier));
        CompletableFuture.allOf(sampled, unsampled).get(20, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void factoryHeaderPrecedesSpanCreationAndPreservesExtractorOverride(boolean executionView) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var calls = new AtomicInteger();
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(new XRayContextExtractor() {
                    @Override
                    public ExtractedContext extract() {
                        calls.incrementAndGet();
                        return super.extract();
                    }
                })
                .build();
        var factory = executionView
                ? ExecutionOtelPlugin.factory(builder, config)
                : InvocationOtelPlugin.factory(builder, config);
        var info =
                invocation("request", "arn", true, Instant.EPOCH, "Root=1-6955b900-123456789012345678901234;Sampled=0");
        var plugin = factory.createPlugin(info);
        plugin.onInvocationEnd(new InvocationEndInfo("request", "arn", true, InvocationStatus.SUCCEEDED, null));
        assertEquals(1, calls.get());
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        var next = factory.createPlugin(new InvocationInfo("request", "arn", true, Instant.EPOCH));
        next.onInvocationEnd(new InvocationEndInfo("request", "arn", true, InvocationStatus.SUCCEEDED, null));
        assertEquals(2, calls.get());
        assertTrue(exporter.getFinishedSpanItems().stream()
                .anyMatch(span -> span.getName().equals("Workflow")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void capturedMissingHeaderDoesNotBorrowTheOverlappingGlobalCarrier(boolean executionView) throws Exception {
        var property = "com.amazonaws.xray.traceHeader";
        var previous = System.getProperty(property);
        var conflictingTrace = "6955b900aaaaaaaaaaaaaaaaaaaaaaaa";
        System.setProperty(property, "Root=1-6955b900-aaaaaaaaaaaaaaaaaaaaaaaa;Sampled=0");
        var barrier = new CyclicBarrier(2);
        var sharedExtractor = new XRayContextExtractor();
        try {
            var missing = CompletableFuture.runAsync(() -> {
                var exporter = InMemorySpanExporter.create();
                var builder = SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter));
                var config = OtelPluginConfig.builder()
                        .enableMdc(false)
                        .contextExtractor(sharedExtractor)
                        .build();
                var factory = executionView
                        ? ExecutionOtelPlugin.factory(builder, config)
                        : InvocationOtelPlugin.factory(builder, config);
                var info = invocation("missing", "arn:missing", true, Instant.EPOCH, "");
                var plugin = factory.createPlugin(info);
                plugin.onInvocationStart(info); // API available, but this invocation has no header.
                await(barrier);
                plugin.onInvocationEnd(
                        new InvocationEndInfo("missing", "arn:missing", true, InvocationStatus.SUCCEEDED, null));
                var spans = exporter.getFinishedSpanItems();
                assertEquals(
                        3,
                        spans.size(),
                        "the synthetic anchor and both SDK spans must retain their own sampling decision");
                assertTrue(spans.stream().allMatch(span -> !conflictingTrace.equals(span.getTraceId())));
            });
            var present = CompletableFuture.runAsync(() -> {
                var extracted = sharedExtractor.extract(invocation(
                        "present",
                        "arn:present",
                        true,
                        Instant.EPOCH,
                        "Root=1-6955b900-123456789012345678901234;Sampled=1"));
                assertEquals("6955b900123456789012345678901234", extracted.traceId());
                assertEquals(ExtractedContext.Sampling.SAMPLED, extracted.sampling());
                await(barrier);
            });
            CompletableFuture.allOf(missing, present).get(20, TimeUnit.SECONDS);
            assertNull(sharedExtractor.extract(invocation("missing", "arn:missing", true, Instant.EPOCH, "")));
            assertEquals(
                    conflictingTrace,
                    sharedExtractor.extract().traceId(),
                    "legacy direct extraction keeps its fallback");
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static void runInvocations(boolean executionView, boolean sampled, CyclicBarrier barrier) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder().enableMdc(false).build();
        DurableExecutionPluginFactory factory = executionView
                ? ExecutionOtelPlugin.factory(builder, config)
                : InvocationOtelPlugin.factory(builder, config);
        var traceId = sampled ? "6955b900123456789012345678901234" : "6955b900aaaaaaaaaaaaaaaaaaaaaaaa";
        var header = "Root=1-" + traceId.substring(0, 8) + "-" + traceId.substring(8)
                + ";Parent=1234567890123456;Sampled=" + (sampled ? "1" : "0");
        var arn = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/" + sampled;
        for (int invocation = 0; invocation < 2; invocation++) {
            var plugin = factory.createPlugin(invocation(
                    "request-" + invocation, arn, invocation == 0, Instant.parse("2026-10-02T00:00:00Z"), header));
            await(barrier);
            plugin.onInvocationEnd(new InvocationEndInfo(
                    "request-" + invocation,
                    arn,
                    invocation == 0,
                    invocation == 0 ? InvocationStatus.PENDING : InvocationStatus.SUCCEEDED,
                    null));
        }
        var spans = exporter.getFinishedSpanItems();
        if (!sampled) {
            assertTrue(spans.isEmpty(), "Upstream Sampled=0 overrides always-on fallback");
            return;
        }
        assertEquals(3, spans.size());
        assertTrue(spans.stream().allMatch(span -> traceId.equals(span.getTraceId())));
        assertTrue(spans.stream().allMatch(span -> "1234567890123456".equals(span.getParentSpanId())));
    }

    private static InvocationInfo invocation(String request, String arn, boolean first, Instant start, String header) {
        return new InvocationInfo(request, arn, first, start, null, Map.of(), Map.of(), header);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
