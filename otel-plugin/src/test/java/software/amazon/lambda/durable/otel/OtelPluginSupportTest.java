// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Sampling input stays independent of the invocation's ambient handler and synthesized ancestors. */
class OtelPluginSupportTest {
    private static final String TRACE_ID = "0123456789abcdef0000000000000001";
    private static final String SPAN_ID = "1111111111111111";

    @ParameterizedTest
    @EnumSource(ExtractedContext.Sampling.class)
    void agentPathAlwaysDefersToInstalledSampler(ExtractedContext.Sampling sampling) {
        var extracted = new ExtractedContext(TRACE_ID, SPAN_ID, sampling);
        assertNull(OtelPluginSupport.resolveSamplingResult(null, extracted, TRACE_ID, "Workflow", Attributes.empty()));
        assertNull(OtelPluginSupport.resolveSamplingResult(null, null, TRACE_ID, "Workflow", Attributes.empty()));
    }

    @ParameterizedTest
    @EnumSource(ExtractedContext.Sampling.class)
    void parentBasedUsesOriginalRemoteParent(ExtractedContext.Sampling sampling) {
        var extracted = new ExtractedContext(TRACE_ID, SPAN_ID, sampling);
        try (var provider = SdkTracerProvider.builder()
                .setSampler(DurableSampler.wrap(Sampler.parentBased(Sampler.alwaysOn())))
                .build()) {
            var result = OtelPluginSupport.resolveSamplingResult(
                    provider, extracted, TRACE_ID, "Workflow", Attributes.empty());
            assertEquals(
                    sampling == ExtractedContext.Sampling.SAMPLED
                            ? SamplingDecision.RECORD_AND_SAMPLE
                            : SamplingDecision.DROP,
                    result.getDecision());
        }
        var parent =
                Span.fromContext(OtelPluginSupport.samplingParent(extracted)).getSpanContext();
        assertTrue(parent.isRemote());
        assertEquals(TRACE_ID, parent.getTraceId());
        assertEquals(SPAN_ID, parent.getSpanId());
        assertEquals(sampling == ExtractedContext.Sampling.SAMPLED, parent.isSampled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "bad-trace", "bad-parent"})
    void unusableParentUsesRootPolicy(String invalid) {
        var extracted = switch (invalid) {
            case "bad-trace" ->
                new ExtractedContext("00000000000000000000000000000000", SPAN_ID, ExtractedContext.Sampling.SAMPLED);
            case "bad-parent" -> new ExtractedContext(TRACE_ID, "0000000000000000", ExtractedContext.Sampling.SAMPLED);
            default -> null;
        };
        assertSame(Context.root(), OtelPluginSupport.samplingParent(extracted));
        try (var provider = SdkTracerProvider.builder()
                .setSampler(DurableSampler.wrap(Sampler.parentBased(Sampler.alwaysOn())))
                .build()) {
            assertEquals(
                    SamplingDecision.RECORD_AND_SAMPLE,
                    OtelPluginSupport.resolveSamplingResult(
                                    provider, extracted, TRACE_ID, "Workflow", Attributes.empty())
                            .getDecision());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ambientNeverOverridesConfiguredPolicy(boolean sampled) {
        var extracted = new ExtractedContext(TRACE_ID, SPAN_ID, ExtractedContext.Sampling.SAMPLED);
        try (var ambientProvider = SdkTracerProvider.builder()
                        .setSampler(sampled ? Sampler.alwaysOn() : Sampler.alwaysOff())
                        .build();
                var provider = SdkTracerProvider.builder()
                        .setSampler(DurableSampler.wrap(Sampler.alwaysOff()))
                        .build()) {
            var ambient = ambientProvider
                    .get("test")
                    .spanBuilder("handler")
                    .setParent(OtelPluginSupport.samplingParent(extracted))
                    .startSpan();
            try (var scope = ambient.makeCurrent()) {
                assertEquals(
                        SamplingDecision.DROP,
                        OtelPluginSupport.resolveSamplingResult(
                                        provider, extracted, TRACE_ID, "Workflow", Attributes.empty())
                                .getDecision());
            } finally {
                ambient.end();
            }
        }
        assertEquals(SpanContext.getInvalid(), Span.current().getSpanContext());
    }
}
