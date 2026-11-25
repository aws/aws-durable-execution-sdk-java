// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Carries the durable execution's sampling intent to {@link DurableSampler} for one durable span.
 *
 * <p>Computed once per invocation (see the plugins' {@code onInvocationStart}), the intent is one of two forms:
 *
 * <ul>
 *   <li>a <b>resolved</b> {@link SamplingResult} from the configured sampler. {@link DurableSampler} returns it
 *       verbatim, preserving the full three-way decision (including {@code RECORD_ONLY}) and consulting no delegate; or
 *   <li>a <b>deferral</b> marker (carrying the canonical trace ID and original propagated parent) — used on the
 *       Java-agent path when the real sampler is not accessible here. {@link DurableSampler} evaluates its actual
 *       delegate once per execution, caches the result by execution ARN and trace ID, and reuses it for the execution's
 *       remaining durable spans while the entry remains cached. This cache does not make stateful or random policies
 *       deterministic across reinvocations or eviction.
 * </ul>
 *
 * <p><b>Two carriers, because the plugin runs across two class loaders.</b> Under the documented ADOT setup the plugin
 * JAR is loaded twice — once by the application class loader (which computes the intent) and once by the Java-agent
 * extension class loader (which installs and runs {@link DurableSampler}). A {@link ContextKey} uses reference
 * identity, so a key created in one loader is not equal to the key created in the other. To bridge this:
 *
 * <ol>
 *   <li><b>Context key</b> — used when both sides share a class loader (an application-owned provider). Agent-backed
 *       spans omit this carrier because another loader cannot consume its key. It preserves the full
 *       {@link SamplingResult}, including any attributes a custom sampler attached.
 *   <li><b>Thread-scoped system property</b> — a cross-class-loader fallback modelled on
 *       {@link DeterministicIdGenerator}'s scoped-ID bridge. The intent is published on the thread that creates the
 *       durable span until its sampler consumes the value (before synchronous span processors run), keyed by thread ID
 *       under a bootstrap-visible {@link System} property so both class loaders read the same value. A resolved
 *       decision bridges its {@link SamplingDecision} name (the three built-in decisions carry no attributes, so
 *       reconstructing them is faithful); a deferral bridges the canonical trace ID and the original parent's W3C trace
 *       context, including flags and trace state. It never samples from a synthesized durable ancestor.
 * </ol>
 *
 * <p>The scope is opened immediately around each durable {@code startSpan()} call and closed right after, so the
 * property is consumed once by the sampler before callbacks can create unrelated spans. Nothing is persisted across
 * invocations; cross-invocation consistency comes from recomputing the intent from stable inputs, not from sharing
 * state.
 */
final class DurableSamplingDecision {

    /**
     * The durable sampling intent for a span: either a resolved {@link SamplingResult}, or a deferral to the agent-side
     * sampler's own delegate. The sampler uses the execution ARN already attached to the span with this trace ID.
     *
     * @param resolved the resolved decision, or null when deferring
     * @param deferredTraceId the canonical trace ID passed to the agent-side delegate, or null when resolved
     * @param samplingParent the original propagated parent context, or root when there is no usable parent
     */
    record Intent(SamplingResult resolved, String deferredTraceId, Context samplingParent) {
        static Intent resolved(SamplingResult result) {
            return new Intent(result, null, Context.root());
        }

        static Intent deferred(String traceId) {
            return deferred(traceId, Context.root());
        }

        static Intent deferred(String traceId, Context samplingParent) {
            return new Intent(null, traceId, samplingParent);
        }

        boolean isDeferred() {
            return resolved == null;
        }
    }

    private static final ContextKey<AtomicReference<Intent>> KEY =
            ContextKey.named("software.amazon.lambda.durable.otel.durable-sampling-decision");

    private static final String SCOPED_PROPERTY_PREFIX = "software.amazon.lambda.durable.otel.scopedSamplingDecision.";
    // Sentinel prefix distinguishing a deferral (carrying the trace ID) from a resolved decision name.
    private static final String DEFERRED_PREFIX = "DEFER:";
    private static final TextMapGetter<Map<String, String>> PROPAGATION_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private DurableSamplingDecision() {}

    /** Returns a context carrying a one-shot intent for one span, preserving the full SamplingResult. */
    static Context store(Context context, Intent intent) {
        return context.with(KEY, new AtomicReference<>(intent));
    }

    /**
     * Publishes the intent on the current thread for the duration of the returned scope, bridging it across the
     * application and Java-agent class loaders. Callers open this immediately around a durable {@code startSpan()} call
     * (which runs the sampler synchronously on this thread) and close it right after.
     */
    static Scope openScope(Intent intent) {
        var key = scopedProperty();
        var previous = System.getProperty(key);
        System.setProperty(key, encode(intent));
        return () -> restoreProperty(key, previous);
    }

    /** Observes whether the actual sampler consumes the intent for this span, including across agent class loaders. */
    static Observation observe(Intent intent, String traceId, String spanName, String executionArn) {
        var key = observationProperty();
        var previous = System.getProperty(key);
        var expected = spanIdentity(traceId, spanName, executionArn);
        var scope = openScope(intent);
        System.setProperty(key, expected);
        return new Observation(scope, key, previous, expected);
    }

    /** Called only by the verified final sampler after it resolves an intent for the span being sampled. */
    static void confirmConsumption(String traceId, String spanName, String executionArn) {
        var key = observationProperty();
        var expected = System.getProperty(key);
        if (expected != null && expected.equals(spanIdentity(traceId, spanName, executionArn))) {
            System.setProperty(key, "CONSUMED:" + expected);
        }
    }

    private static String observationProperty() {
        // An older agent copy that only observes sampler participation must not satisfy final-sampler verification.
        return scopedProperty() + ".verifiedSamplerObservation";
    }

    private static String spanIdentity(String traceId, String spanName, String executionArn) {
        return traceId + "\n" + spanName + "\n" + executionArn;
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    /** A scoped confirmation for one span; it never infers the sampler from the provider's wrapper type. */
    record Observation(Scope scope, String key, String previous, String expected) implements AutoCloseable {
        boolean wasConsumed() {
            return ("CONSUMED:" + expected).equals(System.getProperty(key));
        }

        @Override
        public void close() {
            restoreProperty(key, previous);
            scope.close();
        }
    }

    /** Consumes both carriers before callbacks can reuse the parent context or cross-loader bridge. */
    static Intent consume(Context context) {
        try {
            var holder = context.get(KEY);
            var fromContext = holder != null ? holder.getAndSet(null) : null;
            return fromContext != null ? fromContext : fromScopedProperty();
        } finally {
            System.clearProperty(scopedProperty());
        }
    }

    private static String encode(Intent intent) {
        if (!intent.isDeferred()) return intent.resolved().getDecision().name();
        var carrier = new HashMap<String, String>();
        W3CTraceContextPropagator.getInstance().inject(intent.samplingParent(), carrier, Map::put);
        return DEFERRED_PREFIX + intent.deferredTraceId() + "\n"
                + carrier.getOrDefault("traceparent", "") + "\n"
                + carrier.getOrDefault("tracestate", "");
    }

    private static Intent fromScopedProperty() {
        var value = System.getProperty(scopedProperty());
        if (value == null) {
            return null;
        }
        if (value.startsWith(DEFERRED_PREFIX)) {
            var fields = value.substring(DEFERRED_PREFIX.length()).split("\n", -1);
            var carrier = Map.of("traceparent", fields[1], "tracestate", fields[2]);
            var parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, PROPAGATION_GETTER);
            return Intent.deferred(fields[0], parent);
        }
        return Intent.resolved(
                switch (SamplingDecision.valueOf(value)) {
                    case RECORD_AND_SAMPLE -> SamplingResult.recordAndSample();
                    case RECORD_ONLY -> SamplingResult.recordOnly();
                    case DROP -> SamplingResult.drop();
                });
    }

    private static String scopedProperty() {
        return SCOPED_PROPERTY_PREFIX + Thread.currentThread().getId();
    }

    static void clearSharedStateForTest() {
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith(SCOPED_PROPERTY_PREFIX))
                .toList()
                .forEach(System::clearProperty);
    }

    /** A closeable scope that restores the previous thread-scoped intent when closed. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
