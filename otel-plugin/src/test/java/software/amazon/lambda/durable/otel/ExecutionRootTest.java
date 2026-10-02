// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_ARN;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_STATUS;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_SYNTHETIC_ROOT;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ExecutionRootTest {
    private static final Instant START = Instant.parse("2026-10-02T00:00:00Z");
    private static final String TRACE = "6955b900123456789012345678901234";
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id";

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void anchorIsExportedOnFirstPendingInvocationThenConnectsTerminalWorkflow(boolean executionView, boolean success) {
        var exporter = InMemorySpanExporter.create();
        var plugin = plugin(executionView, exporter, null, Sampler.alwaysOn());
        var effects = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("once", Integer.class, stepCtx -> effects.incrementAndGet());
                    ctx.wait("pause", Duration.ofSeconds(10));
                    if (!success) throw new IllegalArgumentException("expected");
                    return input;
                },
                DurableConfig.builder().withPlugins(plugin).build());
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var first = exporter.getFinishedSpanItems();
        var anchor = named(first, "DurableExecutionRoot");
        assertEquals(true, anchor.getAttributes().get(DURABLE_EXECUTION_SYNTHETIC_ROOT));
        assertFalse(anchor.getParentSpanContext().isValid());
        assertEquals(anchor.getStartEpochNanos(), anchor.getEndEpochNanos());
        assertEquals(StatusCode.UNSET, anchor.getStatus().getStatusCode());
        assertNull(anchor.getAttributes().get(DURABLE_EXECUTION_STATUS));
        assertTrue(first.stream().noneMatch(span -> span.getName().equals("Workflow")));
        assertEquals(anchor.getSpanId(), named(first, "Invocation").getParentSpanId());
        runner.advanceTime();
        assertEquals(
                success ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                runner.runUntilComplete("input").getStatus());
        assertEquals(1, effects.get());
        var all = exporter.getFinishedSpanItems();
        assertEquals(anchor.getSpanId(), named(all, "Workflow").getParentSpanId());
        var anchors = all.stream()
                .filter(span -> span.getName().equals("DurableExecutionRoot"))
                .toList();
        assertEquals(2, anchors.size());
        assertStable(anchor, anchors.get(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fallbackRecoveryKeepsIdentityAndRemoteParentsRemainExternal(boolean executionView) {
        var cases = new ExtractedContext[] {
            null,
            new ExtractedContext("invalid", "bad", ExtractedContext.Sampling.UNDECIDED),
            new ExtractedContext(TRACE, null, ExtractedContext.Sampling.SAMPLED),
            new ExtractedContext(TRACE, "0000000000000000", ExtractedContext.Sampling.SAMPLED)
        };
        for (var extracted : cases) {
            var exporter = InMemorySpanExporter.create();
            var plugin = plugin(executionView, exporter, extracted, Sampler.alwaysOn());
            invoke(plugin, ARN, true, InvocationStatus.RETRYING);
            var root = named(exporter.getFinishedSpanItems(), "DurableExecutionRoot");
            invoke(plugin, ARN, true, InvocationStatus.PENDING); // redelivery after a failed invocation
            invoke(plugin, ARN, false, InvocationStatus.SUCCEEDED);
            var roots = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("DurableExecutionRoot"))
                    .toList();
            assertEquals(3, roots.size());
            roots.forEach(span -> assertStable(root, span));
            assertEquals(new DeterministicIdGenerator().generateExecutionRootSpanId(ARN), root.getSpanId());
            assertEquals(START.getEpochSecond() * 1_000_000_000L, root.getStartEpochNanos());
            if (extracted != null && extracted.hasValidTraceId()) assertEquals(TRACE, root.getTraceId());
        }
        var exporter = InMemorySpanExporter.create();
        var plugin = plugin(
                executionView,
                exporter,
                new ExtractedContext(TRACE, "1234567890123456", ExtractedContext.Sampling.SAMPLED),
                Sampler.alwaysOn());
        invoke(plugin, ARN, true, InvocationStatus.SUCCEEDED);
        assertEquals(2, exporter.getFinishedSpanItems().size());
        assertTrue(exporter.getFinishedSpanItems().stream()
                .allMatch(span -> span.getParentSpanId().equals("1234567890123456")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anchorsHonorSamplingAndAreOwnedByExecutionOnSharedTrace(boolean executionView) {
        for (var extracted :
                new ExtractedContext[] {null, new ExtractedContext(TRACE, null, ExtractedContext.Sampling.NOT_SAMPLED)
                }) {
            var exporter = InMemorySpanExporter.create();
            var plugin = plugin(
                    executionView, exporter, extracted, extracted == null ? Sampler.alwaysOff() : Sampler.alwaysOn());
            invoke(plugin, ARN, true, InvocationStatus.PENDING);
            invoke(plugin, ARN, false, InvocationStatus.SUCCEEDED);
            assertTrue(exporter.getFinishedSpanItems().isEmpty());
        }
        var exporter = InMemorySpanExporter.create();
        var extracted = new ExtractedContext(TRACE, null, ExtractedContext.Sampling.SAMPLED);
        for (var service : List.of("caller", "callee")) {
            var builder = SdkTracerProvider.builder()
                    .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), service)))
                    .setSampler(Sampler.alwaysOff())
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var plugin = plugin(executionView, builder, extracted);
            var start = service.equals("caller") ? START : START.plusSeconds(10);
            invoke(plugin, ARN + "-" + service, true, InvocationStatus.SUCCEEDED, start);
        }
        var roots = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("DurableExecutionRoot"))
                .toList();
        assertEquals(2, roots.size());
        assertTrue(roots.stream().allMatch(span -> span.getTraceId().equals(TRACE)));
        assertNotEquals(roots.get(0).getSpanId(), roots.get(1).getSpanId());
        assertNotEquals(
                roots.get(0).getAttributes().get(DURABLE_EXECUTION_ARN),
                roots.get(1).getAttributes().get(DURABLE_EXECUTION_ARN));
        assertNotEquals(roots.get(0).getStartEpochNanos(), roots.get(1).getStartEpochNanos());
        assertNotEquals(roots.get(0).getResource(), roots.get(1).getResource());
        for (var root : roots) {
            var workflow = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("Workflow"))
                    .filter(span -> span.getAttributes()
                            .get(DURABLE_EXECUTION_ARN)
                            .equals(root.getAttributes().get(DURABLE_EXECUTION_ARN)))
                    .findFirst()
                    .orElseThrow();
            assertEquals(root.getSpanContext(), workflow.getParentSpanContext());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void failedFirstFlushIsRecoveredInAnotherEnvironment(boolean executionView, boolean success) {
        var first = failedSuspendedAnchor(executionView);
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setResource(environmentResource("resumed"))
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        invoke(
                plugin(executionView, builder, null),
                ARN,
                false,
                success ? InvocationStatus.SUCCEEDED : InvocationStatus.FAILED);
        var recovered = named(exporter.getFinishedSpanItems(), "DurableExecutionRoot");
        assertStableSpanFields(first, recovered);
        assertNotEquals(first.getResource(), recovered.getResource());
        assertEquals(
                recovered.getSpanContext(),
                named(exporter.getFinishedSpanItems(), "Workflow").getParentSpanContext());
    }

    private static SpanData failedSuspendedAnchor(boolean executionView) {
        var attempted = new ArrayList<SpanData>();
        try (var processor = BatchSpanProcessor.builder(failingExporter(attempted))
                .setScheduleDelay(Duration.ofDays(1))
                .build()) {
            var builder = SdkTracerProvider.builder()
                    .setResource(environmentResource("first"))
                    .setSampler(Sampler.alwaysOn())
                    .addSpanProcessor(processor);
            invoke(plugin(executionView, builder, null), ARN, true, InvocationStatus.PENDING);
            // The end hook's forceFlush attempts the anchor export before returning, even without a terminal hook.
            assertTrue(attempted.stream().noneMatch(span -> span.getName().equals("Workflow")));
            assertEquals(
                    START.getEpochSecond() * 1_000_000_000L,
                    named(attempted, "DurableExecutionRoot").getStartEpochNanos());
        }
        return named(attempted, "DurableExecutionRoot");
    }

    private static SpanExporter failingExporter(List<SpanData> attempted) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                attempted.addAll(spans);
                return CompletableResultCode.ofFailure();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofFailure();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    private static Resource environmentResource(String instance) {
        return Resource.create(Attributes.of(AttributeKey.stringKey("faas.instance"), instance));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void processorSpansDoNotInheritDurableSamplingOverride(boolean executionView, boolean observeStart) {
        var exporter = InMemorySpanExporter.create();
        var observed = new AtomicReference<SpanContext>();
        try (var unrelated = SdkTracerProvider.builder()
                .setSampler(DurableSampler.wrap(Sampler.alwaysOff()))
                .build()) {
            var builder = SdkTracerProvider.builder()
                    .setSampler(Sampler.alwaysOff())
                    .addSpanProcessor(new SamplingObserver(unrelated, observed, observeStart))
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var config = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> new ExtractedContext(TRACE, null, ExtractedContext.Sampling.SAMPLED))
                    .build();
            DurableExecutionPlugin plugin = executionView
                    ? new ExecutionOtelPlugin(builder, config)
                    : new InvocationOtelPlugin(builder, config);
            invoke(plugin, ARN, true, InvocationStatus.PENDING);
            assertTrue(named(exporter.getFinishedSpanItems(), "DurableExecutionRoot")
                    .getSpanContext()
                    .isSampled());
            assertNotNull(observed.get(), "Root processor callback must have run");
            assertFalse(observed.get().isSampled(), "Processor's unrelated span must retain its always-off policy");
        }
    }

    private record SamplingObserver(
            SdkTracerProvider provider, AtomicReference<SpanContext> observed, boolean observeStart)
            implements SpanProcessor {
        @Override
        public void onStart(Context parent, ReadWriteSpan span) {
            if (observeStart) {
                observe(span);
            }
        }

        @Override
        public boolean isStartRequired() {
            return observeStart;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            if (!observeStart) {
                observe(span);
            }
        }

        private void observe(ReadableSpan span) {
            if (span.getName().equals("DurableExecutionRoot")) {
                var callback =
                        provider.get("processor").spanBuilder("unrelated").startSpan();
                observed.set(callback.getSpanContext());
                callback.end();
            }
        }

        @Override
        public boolean isEndRequired() {
            return !observeStart;
        }
    }

    private static void assertStable(SpanData first, SpanData replay) {
        assertStableSpanFields(first, replay);
        assertEquals(first.getResource(), replay.getResource());
    }

    private static void assertStableSpanFields(SpanData first, SpanData replay) {
        assertEquals(first.getSpanContext(), replay.getSpanContext());
        assertEquals(first.getParentSpanContext(), replay.getParentSpanContext());
        assertEquals(first.getName(), replay.getName());
        assertEquals(first.getKind(), replay.getKind());
        assertEquals(first.getStartEpochNanos(), replay.getStartEpochNanos());
        assertEquals(first.getEndEpochNanos(), replay.getEndEpochNanos());
        assertEquals(first.getAttributes(), replay.getAttributes());
        assertEquals(first.getStatus(), replay.getStatus());
        assertEquals(first.getEvents(), replay.getEvents());
        assertEquals(first.getLinks(), replay.getLinks());
        assertEquals(first.hasEnded(), replay.hasEnded());
        assertEquals(first.getTotalAttributeCount(), replay.getTotalAttributeCount());
        assertEquals(first.getTotalRecordedEvents(), replay.getTotalRecordedEvents());
        assertEquals(first.getTotalRecordedLinks(), replay.getTotalRecordedLinks());
        assertEquals(first.getInstrumentationScopeInfo(), replay.getInstrumentationScopeInfo());
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void invoke(DurableExecutionPlugin plugin, String arn, boolean first, InvocationStatus status) {
        invoke(plugin, arn, first, status, START);
    }

    private static void invoke(
            DurableExecutionPlugin plugin, String arn, boolean first, InvocationStatus status, Instant start) {
        plugin.onInvocationStart(new InvocationInfo("request", arn, first, start));
        plugin.onInvocationEnd(new InvocationEndInfo("request", arn, first, status, null));
    }

    private static DurableExecutionPlugin plugin(
            boolean executionView, InMemorySpanExporter exporter, ExtractedContext extracted, Sampler sampler) {
        var builder =
                SdkTracerProvider.builder().setSampler(sampler).addSpanProcessor(SimpleSpanProcessor.create(exporter));
        return plugin(executionView, builder, extracted);
    }

    private static DurableExecutionPlugin plugin(
            boolean executionView, SdkTracerProviderBuilder builder, ExtractedContext extracted) {
        var config = OtelPluginConfig.builder()
                .contextExtractor(() -> extracted)
                .enableMdc(false)
                .build();
        return executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
    }
}
