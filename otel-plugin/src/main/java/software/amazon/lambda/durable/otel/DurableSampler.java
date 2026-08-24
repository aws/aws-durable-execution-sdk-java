// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A delegating sampler that applies the durable execution's precomputed decision to durable spans and leaves every
 * other span to the wrapped sampler.
 *
 * <p>The durable plugins decide sampling for the whole execution exactly once per invocation and attach the resulting
 * {@link SamplingResult} to the {@link Context} used as the parent of each durable span (via
 * {@link DurableSamplingDecision}). When {@code shouldSample} sees that decision on the parent context it returns it
 * verbatim, so:
 *
 * <ul>
 *   <li>the wrapped (customer-configured or ADOT/community default) sampler is invoked at most once per invocation for
 *       durable spans, which is safe for stateful or quota-based samplers that would otherwise be consumed multiple
 *       times; and
 *   <li>the full decision is preserved, including {@code RECORD_ONLY}, rather than being reduced to a sampled/dropped
 *       bit and re-derived at span creation.
 * </ul>
 *
 * <p>Spans without the durable decision on their parent context (ordinary application or auto-instrumentation spans)
 * are delegated to the wrapped sampler unchanged, so the customer's sampling configuration governs everything outside
 * the durable execution's own spans.
 */
final class DurableSampler implements Sampler {

    // Cap on the deferred-decision cache. The sampler is long-lived (installed once), so an unbounded map would grow
    // per execution on a warm container. A modest LRU cap bounds memory while keeping the "consult once per execution"
    // guarantee for the handful of executions active on one Lambda instance; an evicted entry at worst re-consults the
    // delegate for a later span of that execution, which is a rare, benign degradation rather than a correctness bug.
    private static final int MAX_CACHED_DEFERRED_DECISIONS = 256;

    private final Sampler delegate;
    // Caches the delegate's decision for a deferred durable execution, keyed by canonical trace ID, so a stateful or
    // quota-based delegate is consulted once per execution rather than per span. Access-ordered LRU, size-capped and
    // synchronized (contention is low: at most one miss per execution).
    private final Map<String, SamplingResult> deferredDecisions =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SamplingResult> eldest) {
                    return size() > MAX_CACHED_DEFERRED_DECISIONS;
                }
            });

    private DurableSampler(Sampler delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps {@code delegate} so durable spans use the precomputed decision. A null delegate or an already-wrapped
     * delegate is handled defensively: wrapping is idempotent, and a null delegate falls back to
     * {@link Sampler#parentBased(Sampler)} of {@link Sampler#alwaysOn()} (the OTel/ADOT default) so ordinary spans
     * still have a sampler.
     */
    static DurableSampler wrap(Sampler delegate) {
        if (delegate instanceof DurableSampler durableSampler) {
            return durableSampler;
        }
        var effectiveDelegate = delegate != null ? delegate : Sampler.parentBased(Sampler.alwaysOn());
        return new DurableSampler(effectiveDelegate);
    }

    /**
     * Installs the durable sampler on an application-owned {@link SdkTracerProviderBuilder} by wrapping its configured
     * sampler. The builder exposes only a setter, so the configured sampler is read reflectively (mirroring how the
     * deterministic ID generator wraps the builder's ID generator) and replaced with a wrapper that delegates to it.
     * Installation is idempotent: a builder whose sampler is already a {@link DurableSampler} is left unchanged.
     */
    static void installOn(SdkTracerProviderBuilder builder) {
        var configured = configuredSampler(builder);
        if (configured instanceof DurableSampler) {
            return;
        }
        builder.setSampler(wrap(configured));
    }

    private static Sampler configuredSampler(SdkTracerProviderBuilder builder) {
        for (var field : builder.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !Sampler.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                if (!field.trySetAccessible()) {
                    break;
                }
                return (Sampler) field.get(builder);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to read the configured OpenTelemetry sampler", e);
            }
        }
        throw new IllegalStateException("Unable to locate the configured OpenTelemetry sampler");
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        var intent = DurableSamplingDecision.get(parentContext);
        if (intent == null) {
            // Not a durable span: the customer's sampler governs it unchanged.
            return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        }
        if (!intent.isDeferred()) {
            // A resolved decision (explicit upstream, same-trace ambient, or a locally reproduced sampler): return it
            // verbatim, preserving RECORD_ONLY and never re-invoking the delegate.
            return intent.resolved();
        }
        // Deferred (agent path, real sampler not reproducible here): evaluate the actual delegate once per execution
        // and reuse it, so an installed drop/rate-limit policy is honored and consulted only once.
        return deferredDecisions.computeIfAbsent(
                intent.deferredTraceId(),
                key -> delegate.shouldSample(Context.root(), key, name, spanKind, attributes, Collections.emptyList()));
    }

    @Override
    public String getDescription() {
        return "DurableSampler{" + delegate.getDescription() + "}";
    }
}
