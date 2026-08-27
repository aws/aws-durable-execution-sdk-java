// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;

/**
 * Tests the sampling decision resolved once per invocation: explicit-upstream and same-trace-ambient precedence, and
 * the Java-agent path always deferring to the installed sampler (returning {@code null}).
 */
class OtelPluginSupportTest {

    private static final String TRACE_ID = "aabbccddee112233445566778899aabb";
    private static final String OTHER_TRACE_ID = "11223344556677889900aabbccddeeff";
    private static final String SPAN_ID = "1111111111111111";

    @Test
    void agentPath_defersToInstalledSampler_regardlessOfConfiguredSetting() {
        // On the agent path (provider not visible) the resolver never reconstructs a sampler from configuration — the
        // agent's effective sampler may have been wrapped/replaced by another extension. It always returns null so the
        // agent-installed DurableSampler consults its real delegate. This holds regardless of OTEL_TRACES_SAMPLER.
        assertNull(resolve(null), "No configuration: defer to the installed sampler");
    }

    @Test
    void explicitSampled_isAuthoritative() {
        var extracted = new ExtractedContext(TRACE_ID, SPAN_ID, ExtractedContext.Sampling.SAMPLED);
        assertTrue(sampled(resolve(extracted)), "Explicit upstream Sampled=1 is authoritative");
    }

    @Test
    void explicitNotSampled_isAuthoritative() {
        var extracted = new ExtractedContext(TRACE_ID, SPAN_ID, ExtractedContext.Sampling.NOT_SAMPLED);
        assertFalse(sampled(resolve(extracted)), "Explicit upstream Sampled=0 is authoritative");
    }

    @Test
    void sameTraceAmbient_followsAmbientSampledBit_whenNoExplicitDecision() {
        // No explicit Sampled, and a valid ambient span on the canonical trace: follow the ambient span's decision.
        var ambientSampled = ambient(TRACE_ID, TraceFlags.getSampled(), true);
        assertEquals(
                SamplingDecision.RECORD_AND_SAMPLE,
                resolve(null, ambientSampled).getDecision(),
                "sampled -> sample");

        var ambientNotSampledNotRecording = ambient(TRACE_ID, TraceFlags.getDefault(), false);
        assertEquals(
                SamplingDecision.DROP,
                resolve(null, ambientNotSampledNotRecording).getDecision(),
                "same-trace ambient unsampled and not recording -> DROP");
    }

    @Test
    void sameTraceAmbient_recordOnly_isPreserved() {
        // A RECORD_ONLY ambient span has an unsampled SpanContext but is still recording; reducing it to its sampled
        // bit would DROP durable spans. It must map to RECORD_ONLY so those spans still reach processors.
        var recordOnlyAmbient = ambient(TRACE_ID, TraceFlags.getDefault(), true);
        assertEquals(
                SamplingDecision.RECORD_ONLY,
                resolve(null, recordOnlyAmbient).getDecision(),
                "same-trace ambient unsampled but recording -> RECORD_ONLY");
    }

    @Test
    void differentTraceAmbient_isIgnored_defersOnAgentPath() {
        // An ambient span on a different trace is not representative of this execution and is ignored; with no explicit
        // decision and the provider not visible, the agent path defers to the installed sampler.
        var ambientOtherTrace = ambient(OTHER_TRACE_ID, TraceFlags.getSampled(), true);
        assertNull(resolve(null, ambientOtherTrace), "different-trace ambient ignored -> defer on the agent path");
    }

    /** A span exposing the given context and recording state, for exercising the ambient-decision branch. */
    private static Span ambient(String traceId, TraceFlags flags, boolean recording) {
        var context = SpanContext.create(traceId, SPAN_ID, flags, TraceState.getDefault());
        return new RecordingStateSpan(context, recording);
    }

    private static boolean sampled(io.opentelemetry.sdk.trace.samplers.SamplingResult result) {
        return OtelPluginSupport.isSampled(result);
    }

    private static io.opentelemetry.sdk.trace.samplers.SamplingResult resolve(ExtractedContext extracted) {
        return resolve(extracted, Span.getInvalid());
    }

    private static io.opentelemetry.sdk.trace.samplers.SamplingResult resolve(
            ExtractedContext extracted, Span ambient) {
        // A null sdkTracerProvider exercises the Java-agent path, where the resolver always defers (returns null).
        return OtelPluginSupport.resolveSamplingResult(
                null, extracted, ambient, TRACE_ID, "Workflow", Attributes.empty());
    }

    /**
     * A minimal {@link Span} that only reports a fixed {@link SpanContext} and recording state; all other operations
     * are no-ops. Used to drive the RECORD_ONLY-vs-DROP distinction, which {@code Span.wrap} cannot express because a
     * propagated (wrapped) span is never recording.
     */
    private record RecordingStateSpan(SpanContext spanContext, boolean recording) implements Span {
        @Override
        public SpanContext getSpanContext() {
            return spanContext;
        }

        @Override
        public boolean isRecording() {
            return recording;
        }

        @Override
        public <T> Span setAttribute(io.opentelemetry.api.common.AttributeKey<T> key, T value) {
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, java.util.concurrent.TimeUnit unit) {
            return this;
        }

        @Override
        public Span setStatus(io.opentelemetry.api.trace.StatusCode statusCode, String description) {
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            return this;
        }

        @Override
        public Span updateName(String name) {
            return this;
        }

        @Override
        public void end() {}

        @Override
        public void end(long timestamp, java.util.concurrent.TimeUnit unit) {}
    }
}
