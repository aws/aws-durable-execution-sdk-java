// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class RootStartProcessorIsolationTest {
    private static final String TRACE = "6955b900123456789012345678901234";
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id";
    private static final Instant START = Instant.parse("2026-10-02T00:00:00Z");

    static Stream<Arguments> cases() {
        return Stream.of(true, false)
                .flatMap(view -> Stream.of(true, false)
                        .flatMap(sampled -> Stream.of(true, false)
                                .flatMap(shared -> Stream.of(true, false)
                                        .flatMap(recordOnly -> Stream.of(true, false)
                                                .map(forwardParent -> Arguments.of(
                                                        view, sampled, shared, recordOnly, forwardParent))))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void rootStartCallbacksRetainTheirOwnSamplingAndRandomIds(
            boolean executionView,
            boolean sampled,
            boolean sharedGenerator,
            boolean rootRecordOnly,
            boolean forwardParent) {
        var observer = new StartObserver(forwardParent);
        var policy = sampled ? Sampler.alwaysOn() : Sampler.alwaysOff();
        var builder = SdkTracerProvider.builder()
                .setSampler(rootSampler(rootRecordOnly, policy))
                .addSpanProcessor(observer);
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        TRACE,
                        null,
                        rootRecordOnly ? ExtractedContext.Sampling.UNDECIDED : ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPluginFactory plugin = executionView
                ? ExecutionOtelPlugin.factory(builder, config)
                : InvocationOtelPlugin.factory(builder, config);
        // Rebuilding this configured builder shares the plugin-installed generator; the other branch exercises the
        // same thread-scoped bridge through an independent generator instance.
        try (var unrelated = sharedGenerator
                ? builder.build()
                : SdkTracerProvider.builder()
                        .setSampler(DurableSampler.wrap(policy))
                        .setIdGenerator(new DeterministicIdGenerator())
                        .build()) {
            observer.tracer = unrelated.get("processor");
            var original = Context.current();
            assertControl(observer.tracer, sampled);
            invoke(plugin, true);
            invoke(plugin, false);
            assertControl(observer.tracer, sampled);
            assertSame(original, Context.current());
            assertObservations(observer, sampled);
        }
    }

    private static void invoke(DurableExecutionPluginFactory plugin, boolean first) {
        var instance = plugin.createPlugin(new InvocationInfo("request", ARN, first, START));
        instance.onInvocationEnd(new InvocationEndInfo("request", ARN, first, InvocationStatus.PENDING, null));
    }

    private static void assertControl(Tracer tracer, boolean sampled) {
        var span = tracer.spanBuilder("control").setNoParent().startSpan();
        assertEquals(sampled, span.getSpanContext().isSampled());
        assertEquals(sampled, span.isRecording());
        span.end();
    }

    private static void assertObservations(StartObserver observer, boolean sampled) {
        assertEquals(2, observer.roots.size());
        assertEquals(observer.roots.get(0), observer.roots.get(1));
        var root = observer.roots.get(0);
        assertEquals(new DeterministicIdGenerator().generateExecutionRootSpanId(ARN), root.getSpanId());
        assertEquals(2, observer.callbacks.size());
        assertEquals(List.of(sampled, sampled), observer.recording);
        for (var callback : observer.callbacks) {
            assertEquals(sampled, callback.isSampled());
            assertTrue(callback.isValid());
            assertNotEquals(root.getTraceId(), callback.getTraceId());
            assertNotEquals(root.getSpanId(), callback.getSpanId());
        }
        assertEquals(
                2,
                observer.callbacks.stream()
                        .map(SpanContext::getTraceId)
                        .distinct()
                        .count());
        assertEquals(
                2,
                observer.callbacks.stream()
                        .map(SpanContext::getSpanId)
                        .distinct()
                        .count());
    }

    private static Sampler rootSampler(boolean recordOnly, Sampler policy) {
        if (!recordOnly) return policy;
        return new Sampler() {
            @Override
            public SamplingResult shouldSample(
                    Context parent,
                    String trace,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                return name.equals("Workflow")
                        ? SamplingResult.recordOnly()
                        : policy.shouldSample(parent, trace, name, kind, attributes, links);
            }

            @Override
            public String getDescription() {
                return "RecordOnlyWorkflow";
            }
        };
    }

    private static final class StartObserver implements SpanProcessor {
        private Tracer tracer;
        private final boolean forwardParent;

        private StartObserver(boolean forwardParent) {
            this.forwardParent = forwardParent;
        }

        private final List<SpanContext> roots = new ArrayList<>();
        private final List<SpanContext> callbacks = new ArrayList<>();
        private final List<Boolean> recording = new ArrayList<>();

        @Override
        public void onStart(Context parent, ReadWriteSpan span) {
            // The name guard permits exactly one unrelated span per root and prevents processor recursion.
            if (!span.getName().equals("DurableExecutionRoot")) return;
            roots.add(span.getSpanContext());
            var builder = tracer.spanBuilder("unrelated-onStart");
            var callback = (forwardParent ? builder.setParent(parent) : builder.setNoParent()).startSpan();
            callbacks.add(callback.getSpanContext());
            recording.add(callback.isRecording());
            callback.end();
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {}

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }
}
