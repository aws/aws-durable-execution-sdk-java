// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;

/**
 * Trace context extracted from the Lambda runtime environment.
 *
 * <p>Carries the trace ID, an optional parent span ID (the propagated segment the durable backend nests the invocation
 * under), and the upstream sampling decision. The sampling decision is tri-state: an absent or unusable value is
 * {@link Sampling#UNDECIDED}, which is distinct from an explicit {@link Sampling#NOT_SAMPLED}.
 *
 * @param traceId 32-character lowercase hex trace ID (OTel format, no dashes), or null when no valid Root was present
 * @param parentSpanId 16-character lowercase hex parent span ID, or null when no valid Parent was present
 * @param sampling the upstream sampling decision
 */
public record ExtractedContext(String traceId, String parentSpanId, Sampling sampling) {

    /** Upstream sampling decision carried by the propagated context. */
    public enum Sampling {
        /** The upstream explicitly decided to sample (X-Ray {@code Sampled=1}). */
        SAMPLED,
        /** The upstream explicitly decided not to sample (X-Ray {@code Sampled=0}). */
        NOT_SAMPLED,
        /** No usable upstream decision; the configured sampler decides. */
        UNDECIDED
    }

    /**
     * Normalizes a null sampling decision to {@link Sampling#UNDECIDED}. The canonical constructor is public and is
     * also invoked when a serializer (for example Jackson) reconstructs a legacy value that predates the
     * {@code sampling} component, leaving it null. Downstream sampling resolution switches on the decision, so a null
     * would throw during {@code onInvocationStart} — an exception the plugin runner swallows, silently disabling
     * telemetry for the invocation. Treating an absent decision as {@code UNDECIDED} keeps that path safe and matches
     * the semantics of the two-argument constructor.
     */
    public ExtractedContext {
        if (sampling == null) {
            sampling = Sampling.UNDECIDED;
        }
    }

    /** Creates a context with an undecided sampling decision. */
    public ExtractedContext(String traceId, String parentSpanId) {
        this(traceId, parentSpanId, Sampling.UNDECIDED);
    }

    /** True when the trace ID is a valid, non-zero OTel trace ID. */
    public boolean hasValidTraceId() {
        return traceId != null && TraceId.isValid(traceId);
    }

    /**
     * A complete remote context has a valid trace ID and a valid parent span ID, so it can serve as a remote parent.
     * Validity is stricter than non-null: an all-zero or malformed ID (which an X-Ray Root or a custom extractor may
     * yield) is not usable, and building a parent from it would produce an invalid context that silently splits the
     * execution across separate random traces.
     */
    public boolean hasCompleteRemoteParent() {
        return hasValidTraceId() && parentSpanId != null && SpanId.isValid(parentSpanId);
    }
}
