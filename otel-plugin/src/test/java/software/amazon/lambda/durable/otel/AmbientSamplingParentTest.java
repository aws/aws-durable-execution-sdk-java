// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

/** Uses real OTel SDK handler spans to distinguish DROP from RECORD_ONLY when selecting Invocation parents. */
class AmbientSamplingParentTest {
    private static final String TRACE_ID = "0123456789abcdef0000000000000001";
    private static final String OTHER_TRACE_ID = "fedcba98765432100000000000000001";
    private static final String REMOTE_PARENT_ID = "1234567890abcdef";
    private static final String ARN = "arn:aws:lambda:us-east-1:123:function:test/durable-execution/ambient/id";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    static Stream<Arguments> parents() {
        return Stream.of(false, true)
                .flatMap(executionView -> Stream.of(false, true)
                        .flatMap(remote -> Stream.of("drop", "record_only", "sampled", "different_trace", "missing")
                                .map(ambient -> Arguments.of(executionView, remote, ambient))));
    }

    @ParameterizedTest(name = "execution={0}, remote={1}, ambient={2}")
    @MethodSource("parents")
    void providerDecisionAndAmbientParentSelectionAreIndependent(
            boolean executionView, boolean remote, String ambientState) {
        var original = Context.current();
        try (var durableExporter = InMemorySpanExporter.create();
                var handlerExporter = InMemorySpanExporter.create();
                var handlerProvider = SdkTracerProvider.builder()
                        .setSampler(handlerSampler(ambientState))
                        .addSpanProcessor(SimpleSpanProcessor.create(handlerExporter))
                        .build()) {
            var handler = handlerSpan(handlerProvider, ambientState);
            try (var ignored = Context.root().with(handler).makeCurrent()) {
                assertEquals(
                        ambientState.equals("record_only")
                                || ambientState.equals("sampled")
                                || ambientState.equals("different_trace"),
                        handler.isRecording());
                runInvocation(executionView, remote, durableExporter);
                assertSame(handler, Span.current(), "Durable telemetry must restore the actual handler span");
            } finally {
                handler.end();
            }
            assertSame(original, Context.current());
            assertEquals(
                    ambientState.equals("sampled") || ambientState.equals("different_trace") ? 1 : 0,
                    handlerExporter.getFinishedSpanItems().size(),
                    "Actual handler export follows its own sampler");
            assertParent(durableExporter, handler, ambientState, remote);
        }
    }

    private static void runInvocation(boolean executionView, boolean remote, InMemorySpanExporter exporter) {
        var extracted = new ExtractedContext(
                TRACE_ID,
                remote ? REMOTE_PARENT_ID : null,
                remote ? ExtractedContext.Sampling.NOT_SAMPLED : ExtractedContext.Sampling.UNDECIDED);
        var config = OtelPluginConfig.builder()
                .contextExtractor(() -> extracted)
                .enableMdc(false)
                .build();
        var builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var factory = executionView
                ? ExecutionOtelPlugin.factory(builder, config)
                : InvocationOtelPlugin.factory(builder, config);
        var info = new InvocationInfo("request", ARN, true, START);
        var plugin = factory.createPlugin(info);
        plugin.onInvocationStart(info);
        plugin.onInvocationEnd(new InvocationEndInfo("request", ARN, true, InvocationStatus.SUCCEEDED, null));
    }

    private static void assertParent(InMemorySpanExporter exporter, Span handler, String ambientState, boolean remote) {
        var spans = exporter.getFinishedSpanItems();
        assertTrue(
                spans.stream().anyMatch(span -> span.getName().equals("Workflow")),
                "The durable alwaysOn delegate must override unsampled propagation and ambient decisions");
        assertTrue(spans.stream().allMatch(span -> span.getTraceId().equals(TRACE_ID)));
        var invocation = spans.stream()
                .filter(span -> span.getName().equals("Invocation"))
                .findFirst()
                .orElseThrow();
        var useAmbient = ambientState.equals("record_only") || ambientState.equals("sampled");
        var fallback = remote ? REMOTE_PARENT_ID : new DeterministicIdGenerator().generateExecutionRootSpanId(ARN);
        assertEquals(useAmbient ? handler.getSpanContext().getSpanId() : fallback, invocation.getParentSpanId());
        assertTrue(invocation.getSpanContext().isSampled(), "Choosing an ambient parent must not change sampling");
    }

    private static Span handlerSpan(SdkTracerProvider provider, String state) {
        if (state.equals("missing")) return Span.getInvalid();
        var traceId = state.equals("different_trace") ? OTHER_TRACE_ID : TRACE_ID;
        var parent = SpanContext.createFromRemoteParent(
                traceId, REMOTE_PARENT_ID, TraceFlags.getSampled(), TraceState.getDefault());
        return provider.get("handler")
                .spanBuilder("handler")
                .setParent(Context.root().with(Span.wrap(parent)))
                .startSpan();
    }

    private static Sampler handlerSampler(String state) {
        if (state.equals("drop")) return Sampler.alwaysOff();
        if (!state.equals("record_only")) return Sampler.alwaysOn();
        return new Sampler() {
            @Override
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                return SamplingResult.recordOnly();
            }

            @Override
            public String getDescription() {
                return "record-only-handler";
            }
        };
    }
}
