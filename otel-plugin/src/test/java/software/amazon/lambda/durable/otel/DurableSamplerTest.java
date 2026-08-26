// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionOutcome;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/**
 * Tests the delegating {@link DurableSampler} and its end-to-end effect through the plugin: the configured sampler is
 * evaluated at most once per invocation, the full decision (including {@code RECORD_ONLY}) is preserved, and explicit
 * upstream decisions are authoritative over a conflicting configured sampler.
 */
class DurableSamplerTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";
    private static final String TRACE_ID = "aabbccddee112233445566778899aabb";
    private static final String SPAN_ID = "1111111111111111";

    @BeforeEach
    void setUp() {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    // ─── Unit tests for the wrapper ──────────────────────────────────────

    @Test
    void shouldSample_returnsCarriedDecision_forDurableSpans() {
        var delegate = new CountingSampler(Sampler.alwaysOn());
        var sampler = DurableSampler.wrap(delegate);
        var parent = DurableSamplingDecision.store(
                Context.root(), DurableSamplingDecision.Intent.resolved(SamplingResult.drop()));

        var result = sampler.shouldSample(parent, TRACE_ID, "op", SpanKind.INTERNAL, Attributes.empty(), List.of());

        assertEquals(SamplingDecision.DROP, result.getDecision(), "The carried decision is returned verbatim");
        assertEquals(0, delegate.count(), "The delegate is not invoked when a resolved decision is present");
    }

    @Test
    void shouldSample_preservesRecordOnly() {
        var sampler = DurableSampler.wrap(Sampler.alwaysOn());
        var parent = DurableSamplingDecision.store(
                Context.root(), DurableSamplingDecision.Intent.resolved(SamplingResult.recordOnly()));

        var result = sampler.shouldSample(parent, TRACE_ID, "op", SpanKind.INTERNAL, Attributes.empty(), List.of());

        assertEquals(SamplingDecision.RECORD_ONLY, result.getDecision(), "RECORD_ONLY is preserved, not collapsed");
    }

    @Test
    void shouldSample_delegates_forNonDurableSpans() {
        var delegate = new CountingSampler(Sampler.alwaysOff());
        var sampler = DurableSampler.wrap(delegate);

        var result = sampler.shouldSample(
                Context.root(), TRACE_ID, "other", SpanKind.INTERNAL, Attributes.empty(), List.of());

        assertEquals(SamplingDecision.DROP, result.getDecision(), "Non-durable spans use the delegate");
        assertEquals(1, delegate.count(), "The delegate governs spans without a durable decision");
    }

    @Test
    void wrap_isIdempotent() {
        var wrapped = DurableSampler.wrap(Sampler.alwaysOn());
        assertSame(wrapped, DurableSampler.wrap(wrapped), "Wrapping an already-wrapped sampler returns it unchanged");
    }

    @Test
    void deferredIntent_evaluatesRealDelegate_notBypassed() {
        // Agent path: the real sampler could not be reproduced, so the plugin defers. The wrapper must consult its
        // actual delegate (here always_off) rather than a fabricated sampled decision, so durable spans are dropped.
        var delegate = new CountingSampler(Sampler.alwaysOff());
        var sampler = DurableSampler.wrap(delegate);
        var parent = DurableSamplingDecision.store(Context.root(), DurableSamplingDecision.Intent.deferred(TRACE_ID));

        var result = sampler.shouldSample(parent, TRACE_ID, "op", SpanKind.INTERNAL, Attributes.empty(), List.of());

        assertEquals(SamplingDecision.DROP, result.getDecision(), "The real delegate governs a deferred decision");
    }

    @Test
    void deferredIntent_evaluatesDelegateOncePerExecution() {
        // A stateful/quota delegate must be consulted once per execution (trace ID), not per durable span.
        var delegate = new CountingSampler(Sampler.alwaysOn());
        var sampler = DurableSampler.wrap(delegate);
        var parent = DurableSamplingDecision.store(Context.root(), DurableSamplingDecision.Intent.deferred(TRACE_ID));

        for (var i = 0; i < 4; i++) {
            sampler.shouldSample(parent, TRACE_ID, "op" + i, SpanKind.INTERNAL, Attributes.empty(), List.of());
        }

        assertEquals(
                1, delegate.count(), "The deferred delegate is evaluated once per execution and cached by trace ID");
    }

    @Test
    void agentCustomizer_wrapsEffectiveSampler_evenWhenAnotherExtensionChangedIt() {
        // Another agent extension can wrap or replace the recognized configured sampler before ours runs. Our sampler
        // customizer must wrap whatever effective sampler it receives, so a deferred durable span follows that
        // effective delegate — not a reconstruction from OTEL_TRACES_SAMPLER. Here the effective sampler is always_off
        // (as if a prior customizer replaced a configured always_on), so the deferred durable span is dropped.
        var effectiveSampler = new CountingSampler(Sampler.alwaysOff());
        var installed = captureInstalledSampler(effectiveSampler);

        var parent = DurableSamplingDecision.store(Context.root(), DurableSamplingDecision.Intent.deferred(TRACE_ID));
        var result = installed.shouldSample(parent, TRACE_ID, "op", SpanKind.INTERNAL, Attributes.empty(), List.of());

        assertEquals(
                SamplingDecision.DROP,
                result.getDecision(),
                "The customizer must wrap the effective (possibly replaced) sampler, not a reconstruction");
        assertEquals(1, effectiveSampler.count(), "The effective delegate is the one consulted");
    }

    /** Runs the agent-path sampler customizer over the given effective sampler and returns what it installs. */
    @SuppressWarnings("unchecked")
    private static Sampler captureInstalledSampler(Sampler effectiveSampler) {
        var customizer = org.mockito.Mockito.mock(AutoConfigurationCustomizer.class, org.mockito.Mockito.RETURNS_SELF);
        var samplerCustomizerCaptor = org.mockito.ArgumentCaptor.forClass(BiFunction.class);

        new OtelPluginAutoConfigurationCustomizerProvider().customize(customizer);

        org.mockito.Mockito.verify(customizer).addSamplerCustomizer(samplerCustomizerCaptor.capture());
        BiFunction<Sampler, ConfigProperties, Sampler> samplerCustomizer = samplerCustomizerCaptor.getValue();
        return samplerCustomizer.apply(effectiveSampler, null);
    }

    // ─── End-to-end tests through the plugin ─────────────────────────────

    @Test
    void configuredSampler_isEvaluatedAtMostOncePerInvocation() {
        var delegate = new CountingSampler(Sampler.alwaysOn());
        var exporter = InMemorySpanExporter.create();
        var plugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().setSampler(delegate).addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .build());

        // A full invocation with a Workflow span, Invocation span, operation span, and attempt span.
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "step", "STEP", "Step", null, Instant.now(), null, null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1",
                "step",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                UserFunctionOutcome.SUCCEEDED,
                null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", 1, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        assertTrue(
                delegate.count() <= 1,
                "The configured sampler must be evaluated at most once per invocation, not per span; was "
                        + delegate.count());
    }

    @Test
    void explicitSampled_winsOverConfiguredAlwaysOff() {
        var exporter = exportedWith(Sampler.alwaysOff(), ExtractedContext.Sampling.SAMPLED);
        assertFalse(exporter.getFinishedSpanItems().isEmpty(), "Explicit Sampled=1 exports spans despite always_off");
    }

    @Test
    void explicitNotSampled_winsOverConfiguredAlwaysOn() {
        var exporter = exportedWith(Sampler.alwaysOn(), ExtractedContext.Sampling.NOT_SAMPLED);
        assertTrue(exporter.getFinishedSpanItems().isEmpty(), "Explicit Sampled=0 drops spans despite always_on");
    }

    /**
     * Runs a minimal invocation with the given configured sampler and an extractor that supplies a complete remote
     * parent carrying the given explicit upstream sampling decision, and returns the exporter.
     */
    private InMemorySpanExporter exportedWith(Sampler configuredSampler, ExtractedContext.Sampling sampling) {
        var exporter = InMemorySpanExporter.create();
        var extracted = new ExtractedContext(TRACE_ID, SPAN_ID, sampling);
        var plugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(configuredSampler)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> extracted)
                        .enableMdc(false)
                        .build());

        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));
        return exporter;
    }

    /** A sampler that counts how many times {@code shouldSample} is invoked, delegating the decision. */
    private static final class CountingSampler implements Sampler {
        private final Sampler delegate;
        private final AtomicInteger count = new AtomicInteger();

        CountingSampler(Sampler delegate) {
            this.delegate = delegate;
        }

        int count() {
            return count.get();
        }

        @Override
        public SamplingResult shouldSample(
                Context parentContext,
                String traceId,
                String name,
                SpanKind spanKind,
                Attributes attributes,
                List<LinkData> parentLinks) {
            count.incrementAndGet();
            return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        }

        @Override
        public String getDescription() {
            return "CountingSampler{" + delegate.getDescription() + "}";
        }
    }
}
