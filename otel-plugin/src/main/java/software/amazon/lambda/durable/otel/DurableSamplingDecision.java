// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

/**
 * Carries the durable execution's sampling intent to {@link DurableSampler} for one durable span.
 *
 * <p>Computed once per invocation (see the plugins' {@code onInvocationStart}), the intent is one of two forms:
 *
 * <ul>
 *   <li>a <b>resolved</b> {@link SamplingResult} — an explicit upstream {@code Sampled}, a same-trace ambient span's
 *       bit, or a locally reproducible configured sampler's result. {@link DurableSampler} returns it verbatim,
 *       preserving the full three-way decision (including {@code RECORD_ONLY}) and consulting no delegate; or
 *   <li>a <b>deferral</b> marker (carrying the canonical trace ID) — used on the Java-agent path when the real sampler
 *       (a remote/custom/file-only policy) cannot be reproduced here. {@link DurableSampler} then evaluates its actual
 *       delegate once per execution, caches the result by trace ID, and reuses it for the execution's remaining durable
 *       spans, so an installed drop/rate-limit policy is honored and consulted only once.
 * </ul>
 *
 * <p><b>Two carriers, because the plugin runs across two class loaders.</b> Under the documented ADOT setup the plugin
 * JAR is loaded twice — once by the application class loader (which computes the intent) and once by the Java-agent
 * extension class loader (which installs and runs {@link DurableSampler}). A {@link ContextKey} uses reference
 * identity, so a key created in one loader is not equal to the key created in the other. To bridge this:
 *
 * <ol>
 *   <li><b>Context key</b> — used when both sides share a class loader (an application-owned provider). It preserves
 *       the full {@link SamplingResult}, including any attributes a custom sampler attached.
 *   <li><b>Thread-scoped system property</b> — a cross-class-loader fallback modelled on
 *       {@link DeterministicIdGenerator}'s scoped-ID bridge. The intent is published on the thread that creates the
 *       durable span for the synchronous duration of {@code startSpan()} (the sampler runs on that same thread), keyed
 *       by thread ID under a bootstrap-visible {@link System} property so both class loaders read the same value. A
 *       resolved decision bridges its {@link SamplingDecision} name (the three built-in decisions carry no attributes,
 *       so reconstructing them is faithful); a deferral bridges a sentinel plus the canonical trace ID.
 * </ol>
 *
 * <p>The scope is opened immediately around each durable {@code startSpan()} call and closed right after, so the
 * property never leaks beyond the span it applies to. Nothing is persisted across invocations; cross-invocation
 * consistency comes from recomputing the intent from stable inputs, not from sharing state.
 */
final class DurableSamplingDecision {

    /**
     * The durable sampling intent for a span: either a resolved {@link SamplingResult}, or a deferral to the agent-side
     * sampler's own delegate keyed by the canonical trace ID.
     *
     * @param resolved the resolved decision, or null when deferring
     * @param deferredTraceId the canonical trace ID to key the agent-side delegate cache on, or null when resolved
     */
    record Intent(SamplingResult resolved, String deferredTraceId) {
        static Intent resolved(SamplingResult result) {
            return new Intent(result, null);
        }

        static Intent deferred(String traceId) {
            return new Intent(null, traceId);
        }

        boolean isDeferred() {
            return resolved == null;
        }
    }

    private static final ContextKey<Intent> KEY =
            ContextKey.named("software.amazon.lambda.durable.otel.durable-sampling-decision");

    private static final String SCOPED_PROPERTY_PREFIX = "software.amazon.lambda.durable.otel.scopedSamplingDecision.";
    // Sentinel prefix distinguishing a deferral (carrying the trace ID) from a resolved decision name.
    private static final String DEFERRED_PREFIX = "DEFER:";

    private DurableSamplingDecision() {}

    /** Returns a context carrying the durable sampling intent (same-class-loader carrier), derived from the given. */
    static Context store(Context context, Intent intent) {
        return context.with(KEY, intent);
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
        return () -> {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        };
    }

    /**
     * Returns the durable sampling intent for a span, or {@code null} when none is present. Prefers the full-fidelity
     * context key (same class loader) and falls back to the thread-scoped system property (cross class loader).
     */
    static Intent get(Context context) {
        var fromContext = context.get(KEY);
        if (fromContext != null) {
            return fromContext;
        }
        return fromScopedProperty();
    }

    private static String encode(Intent intent) {
        return intent.isDeferred()
                ? DEFERRED_PREFIX + intent.deferredTraceId()
                : intent.resolved().getDecision().name();
    }

    private static Intent fromScopedProperty() {
        var value = System.getProperty(scopedProperty());
        if (value == null) {
            return null;
        }
        if (value.startsWith(DEFERRED_PREFIX)) {
            return Intent.deferred(value.substring(DEFERRED_PREFIX.length()));
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
