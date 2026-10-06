// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * The per-execution trace context resolved once at invocation start, shared by both plugins.
 *
 * <p>It selects the common execution ancestor that the Workflow and Invocation spans parent onto, so they share one
 * trace that is <em>stable across reinvocations</em>. The ancestor is chosen by precedence:
 *
 * <ol>
 *   <li>a complete remote backend context (valid trace ID + valid parent span ID) is the authoritative ancestor, used
 *       directly whether or not the upstream carries an explicit sampling decision. It is stable per execution;
 *   <li>otherwise a synthetic execution root anchors the execution, with a deterministic span ID in its own namespace
 *       on an ARN/start-time-derived trace ID. This is also stable per execution.
 * </ol>
 *
 * <p>The ambient span ({@code Span.current()}) is deliberately <strong>not</strong> used as the canonical trace or
 * ancestor when the backend context is absent. It is typically a per-invocation Lambda/agent span whose trace ID
 * differs between reinvocations of the same durable execution, so adopting it would give the same execution different
 * trace IDs across invocations and split the Workflow span across traces. When the ambient span is already on the
 * resolved execution trace, the Invocation span parents onto it; otherwise it is not referenced.
 *
 * <p>The canonical trace ID follows the same precedence: the remote trace ID when valid, else one derived from the ARN
 * and start time.
 *
 * <p>IDs are checked for validity, not just non-nullness: an all-zero or malformed trace or span ID (which an X-Ray
 * Root or a custom extractor may yield) is treated as absent, so an invalid Root falls back to the derived trace ID and
 * an invalid Parent drops to the synthetic-root path.
 *
 * <p>Sampling flags: an explicit upstream decision is preserved; when it is absent the configured sampler's decision is
 * applied to both a remote parent and a synthetic root. Trace flags carry a single sampled bit with no "unset" state,
 * so a remote parent left unsampled would make a parent-based sampler drop every child span; deferring to the sampler
 * avoids that.
 *
 * <p>The ancestor is a non-recording context: it is either the external backend server span or a synthetic root the SDK
 * does not export.
 *
 * @param executionAncestor the common parent context for the Workflow and Invocation spans
 */
record ExecutionTraceContext(SpanContext executionAncestor) {

    String traceId() {
        return executionAncestor.getTraceId();
    }

    TraceFlags traceFlags() {
        return executionAncestor.getTraceFlags();
    }

    /**
     * Resolves the execution ancestor by precedence: a valid remote backend parent, else a synthetic root. The ambient
     * span is intentionally not an ancestor — see the class documentation.
     *
     * @param extracted the context parsed from the backend header, or null when none is present
     * @param canonicalTraceId the trace ID the ancestor is anchored on (remote, else ARN-derived)
     * @param arn the durable execution ARN
     * @param idGenerator the deterministic ID generator
     * @param rootSampled the configured sampler's decision for this trace, applied to a remote parent or synthetic root
     *     when the header carries no explicit Sampled value
     */
    static ExecutionTraceContext resolve(
            ExtractedContext extracted,
            String canonicalTraceId,
            String arn,
            DeterministicIdGenerator idGenerator,
            BooleanSupplier rootSampled) {

        // A valid remote backend parent is the authoritative ancestor, regardless of whether Sampled is present.
        if (extracted != null && extracted.hasCompleteRemoteParent()) {
            // Resolve an undecided upstream decision from the configured sampler. Trace flags are a single sampled bit
            // with no "unset" state, so a remote parent built unsampled would make a parent-based sampler drop every
            // child span; defer to the sampler when Sampled is absent, but always preserve an explicit Sampled=0/1. The
            // sampler is only consulted for the UNDECIDED case (lazy), not when the upstream is explicit.
            var flags = explicitFlags(extracted.sampling(), rootSampled);
            // Tracestate is intentionally not propagated: the X-Ray header carries only Root/Parent/Sampled, so there
            // is no upstream tracestate to preserve on this path. An empty TraceState is correct here.
            var remoteParent = SpanContext.createFromRemoteParent(
                    extracted.traceId(), extracted.parentSpanId(), flags, TraceState.getDefault());
            return new ExecutionTraceContext(remoteParent);
        }

        // No backend parent: synthesize an execution root on the canonical (ARN/start-time-derived) trace. This is
        // stable across reinvocations, unlike the per-invocation ambient span.
        var sampling = extracted != null ? extracted.sampling() : ExtractedContext.Sampling.UNDECIDED;
        var syntheticRoot = SpanContext.create(
                canonicalTraceId,
                idGenerator.generateExecutionRootSpanId(arn),
                explicitFlags(sampling, rootSampled),
                TraceState.getDefault());
        return new ExecutionTraceContext(syntheticRoot);
    }

    /**
     * The canonical trace ID by precedence: the remote trace ID when valid, else one derived from the ARN and start
     * time. Both are stable across reinvocations of the same execution. An all-zero or malformed remote trace ID is not
     * usable, so it falls through to the ARN-derived ID rather than anchoring the execution on an invalid trace.
     */
    static String canonicalTraceId(
            ExtractedContext extracted, String arn, Instant executionStartTime, DeterministicIdGenerator idGenerator) {
        if (extracted != null && extracted.hasValidTraceId()) {
            return extracted.traceId();
        }
        return idGenerator.generateTraceIdForExecution(arn, executionStartTime);
    }

    /**
     * Flags for an explicit upstream decision, or the sampler's decision when the upstream did not decide. The sampler
     * is consulted lazily, only for the {@code UNDECIDED} case, so an explicit {@code Sampled=0/1} never triggers a
     * (discarded) sampler query.
     */
    private static TraceFlags explicitFlags(ExtractedContext.Sampling sampling, BooleanSupplier whenUndecided) {
        return switch (sampling) {
            case SAMPLED -> TraceFlags.getSampled();
            case NOT_SAMPLED -> TraceFlags.getDefault();
            case UNDECIDED -> whenUndecided.getAsBoolean() ? TraceFlags.getSampled() : TraceFlags.getDefault();
        };
    }
}
