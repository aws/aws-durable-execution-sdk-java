// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_ARN;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_SYNTHETIC_ROOT;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class ExecutionRootParityTest {
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/parity/id";
    private static final Instant START = Instant.ofEpochSecond(10);
    private static final AttributeKey<String> SAMPLER_VALUE = AttributeKey.stringKey("sampler.invocation");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sampledAnchorEndsBeforeTheStartHookReturns(boolean executionView) {
        try (var exporter = InMemorySpanExporter.create()) {
            var plugin = plugin(executionView, exporter, Sampler.alwaysOn());
            plugin.onInvocationStart(start(true));
            var roots = roots(exporter);
            assertEquals(1, roots.size(), "The anchor is available to processors before handler execution");
            assertEquals(START.getEpochSecond() * 1_000_000_000L, roots.get(0).getStartEpochNanos());
            assertEquals(roots.get(0).getStartEpochNanos(), roots.get(0).getEndEpochNanos());
            plugin.onInvocationEnd(end(true));
            assertEquals(1, roots(exporter).size(), "End does not export the same anchor twice");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anchorReexportsExcludeChangingSamplerMetadataButChildrenKeepIt(boolean executionView) {
        var evaluations = new AtomicInteger();
        var sampler = sampler((name) -> {
            var value = Integer.toString(evaluations.incrementAndGet());
            return new SamplingResult() {
                public SamplingDecision getDecision() {
                    return SamplingDecision.RECORD_AND_SAMPLE;
                }

                public Attributes getAttributes() {
                    return Attributes.of(SAMPLER_VALUE, value);
                }

                public TraceState getUpdatedTraceState(TraceState parent) {
                    return TraceState.builder().put("vendor", value).build();
                }
            };
        });
        try (var exporter = InMemorySpanExporter.create()) {
            var plugin = plugin(executionView, exporter, sampler);
            for (var first : new boolean[] {true, false}) {
                plugin.onInvocationStart(start(first));
                plugin.onInvocationEnd(end(first));
            }
            var roots = roots(exporter);
            assertEquals(2, roots.size());
            assertEquals(2, evaluations.get(), "The anchor must reuse the invocation's sampling result");
            for (var root : roots) {
                assertNull(root.getAttributes().get(SAMPLER_VALUE));
                assertEquals(
                        Attributes.of(DURABLE_EXECUTION_ARN, ARN, DURABLE_EXECUTION_SYNTHETIC_ROOT, true)
                                .asMap(),
                        root.getAttributes().asMap());
                assertEquals(TraceState.getDefault(), root.getSpanContext().getTraceState());
                assertEquals(roots.get(0).getSpanContext(), root.getSpanContext());
            }
            var invocations = exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals("Invocation"))
                    .toList();
            assertEquals(2, invocations.size());
            for (var i = 0; i < invocations.size(); i++) {
                var value = Integer.toString(i + 1);
                assertEquals(value, invocations.get(i).getAttributes().get(SAMPLER_VALUE));
                assertEquals(
                        value,
                        invocations.get(i).getSpanContext().getTraceState().get("vendor"));
                assertEquals(roots.get(i).getSpanContext(), invocations.get(i).getParentSpanContext());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordOnlyStillRecordsChildrenWithoutAnAnchor(boolean executionView) {
        try (var exporter = InMemorySpanExporter.create()) {
            var plugin = plugin(executionView, exporter, sampler(name -> SamplingResult.recordOnly()));
            plugin.onInvocationStart(start(true));
            plugin.onInvocationEnd(end(false));
            assertTrue(roots(exporter).isEmpty());
            var spans = exporter.getFinishedSpanItems();
            assertEquals(2, spans.size());
            assertTrue(spans.stream().allMatch(s -> !s.getSpanContext().isSampled()));
            assertTrue(spans.stream().anyMatch(s -> s.getName().equals("Invocation")));
            assertTrue(spans.stream().anyMatch(s -> s.getName().equals("Workflow")));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void plainReplacementRetainsIndependentPerSpanSampling(boolean executionView, boolean sampleAnchor) {
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        try (var exporter = InMemorySpanExporter.create();
                var provider = SdkTracerProvider.builder()
                        .setIdGenerator(new DeterministicIdGenerator())
                        .setSampler(sampler(name -> name.equals("DurableExecutionRoot") == sampleAnchor
                                ? SamplingResult.recordAndSample()
                                : SamplingResult.drop()))
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            GlobalOpenTelemetry.set(
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            DurableExecutionPlugin plugin =
                    executionView ? new ExecutionOtelPlugin(config()) : new InvocationOtelPlugin(config());
            plugin.onInvocationStart(start(true));
            plugin.onInvocationEnd(end(false));
            assertEquals(sampleAnchor ? 1 : 0, roots(exporter).size());
            assertEquals(
                    sampleAnchor ? 0 : 1,
                    exporter.getFinishedSpanItems().stream()
                            .filter(s -> s.getName().equals("Invocation"))
                            .count());
        } finally {
            GlobalOpenTelemetry.resetForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
        }
    }

    private static DurableExecutionPlugin plugin(
            boolean executionView, InMemorySpanExporter exporter, Sampler sampler) {
        var builder = SdkTracerProvider.builder()
                .setSampler(sampler)
                .addSpanProcessor(SimpleSpanProcessor.builder(exporter)
                        .setExportUnsampledSpans(true)
                        .build());
        return executionView ? new ExecutionOtelPlugin(builder, config()) : new InvocationOtelPlugin(builder, config());
    }

    private static OtelPluginConfig config() {
        return OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> null)
                .build();
    }

    private static InvocationInfo start(boolean first) {
        return new InvocationInfo("request", ARN, first, START);
    }

    private static InvocationEndInfo end(boolean first) {
        return new InvocationEndInfo(
                "request", ARN, first, first ? InvocationStatus.PENDING : InvocationStatus.SUCCEEDED, null);
    }

    private static List<SpanData> roots(InMemorySpanExporter exporter) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("DurableExecutionRoot"))
                .toList();
    }

    private static Sampler sampler(Function<String, SamplingResult> decision) {
        return new Sampler() {
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                return decision.apply(name);
            }

            public String getDescription() {
                return "root-parity";
            }
        };
    }
}
