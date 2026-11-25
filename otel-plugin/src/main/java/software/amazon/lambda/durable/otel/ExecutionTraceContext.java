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
 * <p>Remote ancestor flags describe the original upstream span, including an unset sampled bit. Synthetic ancestor
 * flags use the resolved durable decision. Sampling is evaluated separately using the original propagated parent; the
 * synthesized ancestor is never supplied as a sampling input.
 *
 * <p>The ancestor is a non-recording context: it is either the external backend server span or an SDK-owned synthetic
 * root. Both plugins export the synthetic root before each invocation returns, including suspension, with its stable
 * execution-start timestamp and zero duration. They never export a remote parent.
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
     * @param rootSampled the configured sampler's decision for this trace, applied only to a synthetic root
     */
    static ExecutionTraceContext resolve(
            ExtractedContext extracted,
            String canonicalTraceId,
            String arn,
            DeterministicIdGenerator idGenerator,
            BooleanSupplier rootSampled) {

        // A valid remote backend parent is the authoritative ancestor, regardless of whether Sampled is present.
        if (extracted != null && extracted.hasCompleteRemoteParent()) {
            // The remote parent's flags describe the original upstream span, not the durable decision.
            var flags = extracted.sampling() == ExtractedContext.Sampling.SAMPLED
                    ? TraceFlags.getSampled()
                    : TraceFlags.getDefault();
            // Tracestate is intentionally not propagated: the X-Ray header carries only Root/Parent/Sampled, so there
            // is no upstream tracestate to preserve on this path. An empty TraceState is correct here.
            var remoteParent = SpanContext.createFromRemoteParent(
                    extracted.traceId(), extracted.parentSpanId(), flags, TraceState.getDefault());
            return new ExecutionTraceContext(remoteParent);
        }

        // No backend parent: synthesize an execution root on the canonical (ARN/start-time-derived) trace. This is
        // stable across reinvocations, unlike the per-invocation ambient span.
        var syntheticRoot = SpanContext.create(
                canonicalTraceId,
                idGenerator.generateExecutionRootSpanId(arn),
                rootSampled.getAsBoolean() ? TraceFlags.getSampled() : TraceFlags.getDefault(),
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
}
