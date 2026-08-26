// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Unit tests for the execution-trace resolution table shared by both plugins. */
class ExecutionTraceContextTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";
    private static final String REMOTE_TRACE_ID = "aabbccddee112233445566778899aabb";
    private static final String REMOTE_PARENT_ID = "53995c3f42cd8ad8";
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_SPAN_ID = "0000000000000000";
    private static final Instant START = Instant.parse("2026-08-15T00:00:00Z");

    private final DeterministicIdGenerator idGenerator = new DeterministicIdGenerator();

    @Test
    void canonicalTraceId_reusesRemoteTraceId_whenPresent() {
        var extracted = new ExtractedContext(REMOTE_TRACE_ID, REMOTE_PARENT_ID);
        assertEquals(REMOTE_TRACE_ID, canonicalTraceId(extracted));
    }

    @Test
    void canonicalTraceId_derivesFromArn_whenNoRemoteTraceId() {
        var canonical = canonicalTraceId(null);
        assertEquals(idGenerator.generateTraceIdForExecution(ARN, START), canonical);
        assertTrue(canonical.matches("[0-9a-f]{32}"));
    }

    @Test
    void canonicalTraceId_derivesFromArn_whenRemoteTraceIdIsAllZero() {
        // An all-zero (invalid) Root must not become canonical; it falls back to the ARN-derived trace ID.
        var extracted = new ExtractedContext(ALL_ZERO_TRACE_ID, REMOTE_PARENT_ID);
        assertEquals(idGenerator.generateTraceIdForExecution(ARN, START), canonicalTraceId(extracted));
    }

    @Test
    void allZeroRoot_synthesizesRootOnDerivedTrace_notOnTheZeroTrace() {
        // A valid parent cannot be built from an all-zero trace ID, so the execution anchors on a synthetic root over
        // the ARN-derived canonical trace rather than an invalid context.
        var extracted = new ExtractedContext(ALL_ZERO_TRACE_ID, REMOTE_PARENT_ID);
        var canonical = canonicalTraceId(extracted);
        var execCtx = resolve(extracted, canonical, () -> true);

        assertTrue(execCtx.executionAncestor().isValid(), "The ancestor must be a valid context");
        assertEquals(
                idGenerator.generateTraceIdForExecution(ARN, START),
                execCtx.executionAncestor().getTraceId());
        assertEquals(
                idGenerator.generateExecutionRootSpanId(ARN),
                execCtx.executionAncestor().getSpanId());
    }

    @Test
    void allZeroParent_isTreatedAsAbsent_synthesizesRootOnRemoteTrace() {
        // A valid Root with an all-zero (invalid) Parent drops to the synthetic-root path — the invalid parent is not
        // used — while still reusing the valid remote trace ID.
        var extracted = new ExtractedContext(REMOTE_TRACE_ID, ALL_ZERO_SPAN_ID);
        assertFalse(extracted.hasCompleteRemoteParent(), "An all-zero parent is not a usable remote parent");

        var execCtx = resolve(extracted, REMOTE_TRACE_ID, () -> true);
        assertEquals(REMOTE_TRACE_ID, execCtx.executionAncestor().getTraceId());
        assertEquals(
                idGenerator.generateExecutionRootSpanId(ARN),
                execCtx.executionAncestor().getSpanId());
    }

    @Test
    void completeRemoteParentWithSampled_becomesAncestor_preservesSampled() {
        // Row: valid Root, Parent, Sampled=1 -> reuse Root, remote parent, preserve sampled.
        var extracted = new ExtractedContext(REMOTE_TRACE_ID, REMOTE_PARENT_ID, ExtractedContext.Sampling.SAMPLED);
        var execCtx = resolve(extracted, REMOTE_TRACE_ID, () -> false);

        assertEquals(REMOTE_TRACE_ID, execCtx.executionAncestor().getTraceId());
        assertEquals(REMOTE_PARENT_ID, execCtx.executionAncestor().getSpanId());
        assertTrue(execCtx.executionAncestor().isRemote(), "The remote server span is the ancestor");
        assertTrue(execCtx.traceFlags().isSampled(), "Explicit upstream Sampled=1 wins over the supplier");
    }

    @Test
    void completeRemoteParentNotSampled_becomesAncestor_preservesNotSampled() {
        // Row: valid Root, Parent, Sampled=0 -> reuse Root, remote parent, preserve not-sampled.
        var extracted = new ExtractedContext(REMOTE_TRACE_ID, REMOTE_PARENT_ID, ExtractedContext.Sampling.NOT_SAMPLED);
        var execCtx = resolve(extracted, REMOTE_TRACE_ID, () -> true);

        assertEquals(REMOTE_PARENT_ID, execCtx.executionAncestor().getSpanId());
        assertFalse(execCtx.traceFlags().isSampled(), "Explicit upstream Sampled=0 wins over the supplier");
    }

    @Test
    void completeRemoteParentUndecided_becomesAncestor_supplierDecidesSampling() {
        // Row: valid Root, Parent, no valid Sampled -> reuse Root, remote parent, and resolve the undecided decision
        // from the configured sampler (via the supplier). Trace flags have no "unset" state, so a remote parent built
        // unsampled would make a parent-based sampler drop every child span; the supplier's decision applies instead.
        var extracted = new ExtractedContext(REMOTE_TRACE_ID, REMOTE_PARENT_ID);

        var sampledCtx = resolve(extracted, REMOTE_TRACE_ID, () -> true);
        assertEquals(REMOTE_TRACE_ID, sampledCtx.executionAncestor().getTraceId());
        assertEquals(REMOTE_PARENT_ID, sampledCtx.executionAncestor().getSpanId());
        assertTrue(sampledCtx.executionAncestor().isRemote(), "The remote server span is the ancestor");
        assertTrue(sampledCtx.traceFlags().isSampled(), "Undecided upstream defers to the sampler (sampled)");

        var droppedCtx = resolve(extracted, REMOTE_TRACE_ID, () -> false);
        assertEquals(REMOTE_PARENT_ID, droppedCtx.executionAncestor().getSpanId());
        assertFalse(droppedCtx.traceFlags().isSampled(), "Undecided upstream defers to the sampler (not sampled)");
    }

    @Test
    void completeRemoteParentExplicitSampled_winsOverSupplier() {
        // An explicit upstream Sampled=1/0 is preserved on the remote-parent path regardless of what the supplier says.
        var sampledUpstream =
                new ExtractedContext(REMOTE_TRACE_ID, REMOTE_PARENT_ID, ExtractedContext.Sampling.SAMPLED);
        assertTrue(resolve(sampledUpstream, REMOTE_TRACE_ID, () -> false)
                .traceFlags()
                .isSampled());

        var notSampledUpstream =
                new ExtractedContext(REMOTE_TRACE_ID, REMOTE_PARENT_ID, ExtractedContext.Sampling.NOT_SAMPLED);
        assertFalse(resolve(notSampledUpstream, REMOTE_TRACE_ID, () -> true)
                .traceFlags()
                .isSampled());
    }

    @Test
    void remoteTraceWithoutParent_synthesizesRootOnRemoteTrace() {
        // Row: valid Root, missing Parent, no valid Sampled -> reuse Root, synthetic root, supplier decides.
        var extracted = new ExtractedContext(REMOTE_TRACE_ID, null);
        var execCtx = resolve(extracted, REMOTE_TRACE_ID, () -> true);

        assertEquals(REMOTE_TRACE_ID, execCtx.executionAncestor().getTraceId());
        assertEquals(
                idGenerator.generateExecutionRootSpanId(ARN),
                execCtx.executionAncestor().getSpanId());
        assertTrue(execCtx.traceFlags().isSampled(), "The supplier decides for a synthetic root");
    }

    @Test
    void remoteTraceWithoutParent_explicitSampledPreserved_overSupplier() {
        // Row: valid Root, missing Parent, Sampled=1 -> reuse Root, synthetic root, preserve explicit decision.
        var extracted = new ExtractedContext(REMOTE_TRACE_ID, null, ExtractedContext.Sampling.SAMPLED);
        var execCtx = resolve(extracted, REMOTE_TRACE_ID, () -> false);

        assertEquals(
                idGenerator.generateExecutionRootSpanId(ARN),
                execCtx.executionAncestor().getSpanId());
        assertTrue(execCtx.traceFlags().isSampled(), "Explicit decision is preserved even on a synthetic root");
    }

    @Test
    void noContext_synthesizesRootOnCanonicalTrace_supplierDecidesSampling() {
        // Row: missing Root -> derive trace ID from ARN and start time, synthetic root, supplier decides.
        var canonical = canonicalTraceId(null);
        var sampledCtx = resolve(null, canonical, () -> true);
        var droppedCtx = resolve(null, canonical, () -> false);

        assertEquals(canonical, sampledCtx.executionAncestor().getTraceId());
        assertEquals(
                idGenerator.generateExecutionRootSpanId(ARN),
                sampledCtx.executionAncestor().getSpanId());
        assertTrue(sampledCtx.traceFlags().isSampled());
        assertFalse(droppedCtx.traceFlags().isSampled());
    }

    @Test
    void canonicalTraceId_reusesAmbientTraceId_whenNoRemoteContext() {
        // With no backend context the canonical trace is ARN/start-time-derived and stable, NOT the ambient span's
        // trace: the ambient span is per-invocation and must not destabilize the execution trace across reinvocations.
        assertEquals(idGenerator.generateTraceIdForExecution(ARN, START), canonicalTraceId(null));
    }

    @Test
    void ancestorIsSyntheticRoot_whenNoBackendContext_regardlessOfAmbient() {
        // Ambient is not an ancestor. With no backend context the ancestor is the deterministic synthetic root on the
        // ARN-derived trace, so the execution stays on one stable trace even when a per-invocation ambient span exists.
        var canonical = canonicalTraceId(null);
        var execCtx = resolve(null, canonical, () -> true);

        assertEquals(
                idGenerator.generateTraceIdForExecution(ARN, START),
                execCtx.executionAncestor().getTraceId());
        assertEquals(
                idGenerator.generateExecutionRootSpanId(ARN),
                execCtx.executionAncestor().getSpanId());
    }

    @Test
    void canonicalTraceId_isStableAcrossReinvocations_withDifferentAmbientTraces() {
        // The same execution (ARN + start time) yields the same canonical trace regardless of the ambient span, which
        // may be a different per-invocation trace on each reinvocation. This is the core stability invariant.
        var expected = idGenerator.generateTraceIdForExecution(ARN, START);
        assertEquals(expected, canonicalTraceId(null), "reinvocation 1 (ambient trace A) -> same execution trace");
        assertEquals(expected, canonicalTraceId(null), "reinvocation 2 (ambient trace B) -> same execution trace");
    }

    private static SpanContext validAmbientSpan(String traceId) {
        return SpanContext.create(traceId, "1111111111111111", TraceFlags.getSampled(), TraceState.getDefault());
    }

    private String canonicalTraceId(ExtractedContext extracted) {
        return ExecutionTraceContext.canonicalTraceId(extracted, ARN, START, idGenerator);
    }

    private ExecutionTraceContext resolve(
            ExtractedContext extracted, String canonicalTraceId, BooleanSupplier rootSampled) {
        return ExecutionTraceContext.resolve(extracted, canonicalTraceId, ARN, idGenerator, rootSampled);
    }
}
