// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static software.amazon.lambda.durable.otel.DurableSamplingDecisionClassLoaderTest.pluginClassLoader;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeferredSamplingParentTest {
    private static final String SAMPLED_TRACE = "6955b900123456780000000000000001";
    private static final String DROPPED_TRACE = "6955b900123456787fffffffffffffff";
    private static final String REMOTE_PARENT = "1234567890123456";
    private static final String SYNTHETIC_PARENT = "fedcba9876543210";
    private static final String ARN = "arn:aws:lambda:us-east-1:123:function:test/durable-execution/sampling/id";
    private static final Attributes ATTRIBUTES = Attributes.of(SpanAttributes.DURABLE_EXECUTION_ARN, ARN);

    @AfterEach
    void clearBridge() {
        DurableSamplingDecision.clearSharedStateForTest();
    }

    @ParameterizedTest
    @MethodSource("policyCases")
    void deferredPolicyUsesOriginalParentAcrossClassLoaders(
            boolean foreign, String policy, String parentState, boolean belowThreshold, boolean expected)
            throws Exception {
        var traceId = belowThreshold ? SAMPLED_TRACE : DROPPED_TRACE;
        var parent = samplingParent(traceId, parentState);
        try (var loader = pluginClassLoader()) {
            var sampler = wrap(sampler(policy), foreign, loader);
            for (var spanName : List.of("Workflow", "Invocation", "Step: work", "StepAttempt: work")) {
                var result = sample(sampler, traceId, parent, spanName);
                assertEquals(
                        expected ? SamplingDecision.RECORD_AND_SAMPLE : SamplingDecision.DROP,
                        result.getDecision(),
                        spanName);
            }
            assertNull(DurableSamplingDecision.consume(Context.root()));
        }
    }

    static Stream<Arguments> policyCases() {
        return Stream.of(false, true)
                .flatMap(foreign -> Stream.of(
                                "always_on",
                                "always_off",
                                "traceidratio",
                                "parentbased_always_on",
                                "parentbased_always_off",
                                "parentbased_traceidratio")
                        .flatMap(policy -> Stream.of("sampled", "unsampled", "unset", "none")
                                .flatMap(parent -> Stream.of(false, true)
                                        .map(below -> Arguments.of(
                                                foreign, policy, parent, below, expected(policy, parent, below))))));
    }

    private static boolean expected(String policy, String parent, boolean belowThreshold) {
        if (policy.startsWith("parentbased_") && !parent.equals("none")) return parent.equals("sampled");
        return switch (policy.replace("parentbased_", "")) {
            case "always_on" -> true;
            case "always_off" -> false;
            default -> belowThreshold;
        };
    }

    private static Sampler sampler(String policy) {
        var root = switch (policy.replace("parentbased_", "")) {
            case "always_on" -> Sampler.alwaysOn();
            case "always_off" -> Sampler.alwaysOff();
            default -> Sampler.traceIdRatioBased(0.5);
        };
        return policy.startsWith("parentbased_") ? Sampler.parentBased(root) : root;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deferredParentAndFullResultSurviveCacheAndColdProvider(boolean foreign) throws Exception {
        var remote = SpanContext.createFromRemoteParent(
                SAMPLED_TRACE,
                REMOTE_PARENT,
                TraceFlags.getDefault(),
                TraceState.builder()
                        .put("vendor", "alpha;beta")
                        .put("other", "kept")
                        .build());
        var parent = Context.root().with(Span.wrap(remote));
        var calls = new AtomicInteger();
        var result = metadataResult();
        var delegate = observingSampler(remote, calls, result);
        try (var loader = pluginClassLoader()) {
            var sampler = wrap(delegate, foreign, loader);
            for (var invocation : List.of("first", "resumed")) {
                assertSame(result, sample(sampler, SAMPLED_TRACE, parent, invocation));
                assertSame(result, sample(sampler, SAMPLED_TRACE, parent, "operation"));
            }
            assertEquals(1, calls.get(), "A resident execution reuses the complete result");
            assertSame(result, sample(wrap(delegate, foreign, loader), SAMPLED_TRACE, parent, "cold-resume"));
            assertEquals(2, calls.get(), "A fresh provider recomputes from the same original parent");
        }
    }

    private static Sampler observingSampler(SpanContext remote, AtomicInteger calls, SamplingResult result) {
        return new Sampler() {
            @Override
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                assertEquals(remote, Span.fromContext(parent).getSpanContext());
                assertEquals(SAMPLED_TRACE, traceId);
                calls.incrementAndGet();
                return result;
            }

            @Override
            public String getDescription() {
                return "stable-parent-metadata";
            }
        };
    }

    private static SamplingResult metadataResult() {
        return new SamplingResult() {
            @Override
            public SamplingDecision getDecision() {
                return SamplingDecision.RECORD_ONLY;
            }

            @Override
            public Attributes getAttributes() {
                return Attributes.of(AttributeKey.stringKey("sampler.metadata"), "kept");
            }

            @Override
            public TraceState getUpdatedTraceState(TraceState parent) {
                return parent.toBuilder().put("decision", "kept").build();
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deferredRootCannotInheritSyntheticParentAndDoesNotAffectOrdinarySpans(boolean foreign) throws Exception {
        try (var loader = pluginClassLoader()) {
            var sampler = wrap(Sampler.parentBased(Sampler.alwaysOff()), foreign, loader);
            assertEquals(
                    SamplingDecision.DROP,
                    sample(sampler, SAMPLED_TRACE, Context.root(), "Invocation").getDecision());
            assertEquals(
                    SamplingDecision.RECORD_AND_SAMPLE,
                    sampler.shouldSample(
                                    syntheticParent(SAMPLED_TRACE),
                                    SAMPLED_TRACE,
                                    "ordinary-application",
                                    SpanKind.INTERNAL,
                                    Attributes.empty(),
                                    List.of())
                            .getDecision());
        }
    }

    private static Context samplingParent(String traceId, String state) {
        if (state.equals("none")) return Context.root();
        var flags = state.equals("sampled") ? TraceFlags.getSampled() : TraceFlags.getDefault();
        return Context.root()
                .with(Span.wrap(
                        SpanContext.createFromRemoteParent(traceId, REMOTE_PARENT, flags, TraceState.getDefault())));
    }

    private static Context syntheticParent(String traceId) {
        return Context.root()
                .with(Span.wrap(SpanContext.create(
                        traceId, SYNTHETIC_PARENT, TraceFlags.getSampled(), TraceState.getDefault())));
    }

    private static SamplingResult sample(Sampler sampler, String traceId, Context parent, String name) {
        var intent = DurableSamplingDecision.Intent.deferred(traceId, parent);
        var spanParent = DurableSamplingDecision.store(syntheticParent(traceId), intent);
        try (var ignored = DurableSamplingDecision.openScope(intent)) {
            return sampler.shouldSample(spanParent, traceId, name, SpanKind.INTERNAL, ATTRIBUTES, List.of());
        }
    }

    private static Sampler wrap(Sampler delegate, boolean foreign, URLClassLoader loader) throws Exception {
        if (!foreign) return DurableSampler.wrap(delegate);
        var samplerType = Class.forName(DurableSampler.class.getName(), true, loader);
        var wrap = samplerType.getDeclaredMethod("wrap", Sampler.class);
        wrap.setAccessible(true);
        return (Sampler) wrap.invoke(null, delegate);
    }
}
