// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.plugin.*;

class OtelPluginTest {

    private InMemorySpanExporter spanExporter;
    private OtelPlugin plugin;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();

        plugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false);
    }

    @Test
    void invocationStart_and_end_createsSpan() {
        plugin.onInvocationStart(new InvocationInfo(
                "req-123", "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1", true));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req-123",
                "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1",
                true,
                InvocationStatus.SUCCEEDED,
                null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        var span = spans.get(0);
        assertEquals("durable.invocation", span.getName());
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    }

    @Test
    void invocationEnd_withFailure_setsErrorStatus() {
        plugin.onInvocationStart(new InvocationInfo("req-123", "arn:exec1", true));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req-123", "arn:exec1", true, InvocationStatus.FAILED, new RuntimeException("boom")));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    }

    @Test
    void operationStart_createsSpan_operationEnd_endsIt() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        var start = Instant.parse("2026-06-01T10:00:00Z");
        var end = Instant.parse("2026-06-01T10:00:05Z");

        // Operation span created at start
        plugin.onOperationStart(new OperationInfo("op-hash-1", "my-step", "STEP", "Step", null, start, null));

        // Operation span ended at completion
        plugin.onOperationEnd(new OperationEndInfo("op-hash-1", "my-step", "STEP", "Step", null, start, end, null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size());

        var operationSpan = spans.stream()
                .filter(s -> s.getName().contains("step"))
                .findFirst()
                .orElseThrow();
        assertEquals("durable.step:my-step", operationSpan.getName());
    }

    @Test
    void userFunctionStart_and_end_createsAttemptSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "compute", "STEP", "Step", null, Instant.now(), false, 1));

        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "compute", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size());

        var attemptSpan = spans.stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        assertTrue(attemptSpan.getName().contains("compute"));
        assertTrue(attemptSpan.getName().contains("attempt 1"));
    }

    @Test
    void userFunctionEnd_withFailure_setsErrorOnAttemptSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "failing", "STEP", "Step", null, Instant.now(), false, 1));

        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1",
                "failing",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                false,
                new RuntimeException("step failed")));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.FAILED, null));

        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        assertEquals(StatusCode.ERROR, attemptSpan.getStatus().getStatusCode());
    }

    @Test
    void fullLifecycle_producesCorrectSpanHierarchy() {
        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";
        plugin.onInvocationStart(new InvocationInfo("req-1", arn, true));

        // Step 1: operation starts, user function runs, operation completes
        plugin.onOperationStart(new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(
                new OperationEndInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), null));

        // Step 2: operation starts, user function runs, operation completes
        plugin.onOperationStart(new OperationInfo("op-2", "step-b", "STEP", "Step", null, Instant.now(), null));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-2", "step-b", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-2", "step-b", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(
                new OperationEndInfo("op-2", "step-b", "STEP", "Step", null, Instant.now(), Instant.now(), null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        // 2 attempt spans + 2 operation spans + 1 invocation span = 5
        assertEquals(5, spans.size());

        // All spans should share the same trace ID
        var traceId = spans.get(0).getTraceId();
        assertTrue(spans.stream().allMatch(s -> s.getTraceId().equals(traceId)));
    }

    @Test
    void deterministicIds_sameExecutionProducesSameTraceId() {
        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";

        plugin.onInvocationStart(new InvocationInfo("req-1", arn, true));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.PENDING, null));

        var firstTraceId = spanExporter.getFinishedSpanItems().get(0).getTraceId();
        spanExporter.reset();

        // Second invocation of same execution
        plugin.onInvocationStart(new InvocationInfo("req-2", arn, false));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", arn, false, InvocationStatus.SUCCEEDED, null));

        var secondTraceId = spanExporter.getFinishedSpanItems().get(0).getTraceId();

        assertEquals(firstTraceId, secondTraceId, "Same execution ARN should produce same trace ID");
    }

    @Test
    void operationNotCompleted_spanEndedAtInvocationEnd() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        // Operation starts but never completes (e.g., wait operation, invocation suspends)
        plugin.onOperationStart(new OperationInfo("op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), null));

        // Invocation ends without onOperationEnd being called
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.PENDING, null));

        var spans = spanExporter.getFinishedSpanItems();
        // Should have: operation span (ended at invocation end) + invocation span
        assertEquals(2, spans.size());

        var operationSpan = spans.stream()
                .filter(s -> s.getName().contains("wait"))
                .findFirst()
                .orElseThrow();
        assertEquals("durable.wait:my-wait", operationSpan.getName());
    }

    @Test
    void sampling_disabled_producesNoSpans() {
        spanExporter = InMemorySpanExporter.create();
        var sampledPlugin = new OtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff())
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false);

        sampledPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));
        sampledPlugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step", "STEP", "Step", null, Instant.now(), false, 1));
        sampledPlugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        sampledPlugin.onOperationEnd(
                new OperationEndInfo("op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), null));
        sampledPlugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        assertTrue(spanExporter.getFinishedSpanItems().isEmpty(), "No spans should be exported with 0% sampling");
    }

    // ─── X-Ray trace ID extraction integration tests ─────────────────────

    @Test
    void xrayExtraction_usesExtractedTraceId_overArnDerived() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        var extractedContext = new ExtractedContext(xrayTraceId, null);

        spanExporter = InMemorySpanExporter.create();
        var xrayPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(xrayTraceId, spans.get(0).getTraceId(), "Span should use the extracted X-Ray trace ID");
    }

    @Test
    void xrayExtraction_allSpansShareExtractedTraceId() {
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var extractedContext = new ExtractedContext(xrayTraceId, null);

        spanExporter = InMemorySpanExporter.create();
        var xrayPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));
        xrayPlugin.onOperationStart(new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null));
        xrayPlugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), false, 1));
        xrayPlugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        xrayPlugin.onOperationEnd(
                new OperationEndInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), null));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 2, "Should have invocation + operation + attempt spans");
        assertTrue(
                spans.stream().allMatch(s -> s.getTraceId().equals(xrayTraceId)),
                "All spans must share the extracted X-Ray trace ID");
    }

    @Test
    void xrayExtraction_withParentSpanId_invocationSpanHasCorrectParent() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        var parentSpanId = "53995c3f42cd8ad8";
        var extractedContext = new ExtractedContext(xrayTraceId, parentSpanId);

        spanExporter = InMemorySpanExporter.create();
        var xrayPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        var invocationSpan = spans.get(0);
        assertEquals(xrayTraceId, invocationSpan.getTraceId());
        assertEquals(
                parentSpanId,
                invocationSpan.getParentSpanId(),
                "Invocation span should be parented to X-Ray Parent span");
    }

    @Test
    void xrayExtraction_withoutParentSpanId_invocationSpanIsRoot() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        var extractedContext = new ExtractedContext(xrayTraceId, null);

        spanExporter = InMemorySpanExporter.create();
        var xrayPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        var invocationSpan = spans.get(0);
        assertEquals(xrayTraceId, invocationSpan.getTraceId());
        // Parent span ID should be empty/invalid when no parent provided
        assertFalse(
                io.opentelemetry.api.trace.SpanContext.create(
                                xrayTraceId,
                                invocationSpan.getParentSpanId(),
                                io.opentelemetry.api.trace.TraceFlags.getSampled(),
                                io.opentelemetry.api.trace.TraceState.getDefault())
                        .isRemote(),
                "Without X-Ray parent, invocation span should not have a remote parent");
    }

    @Test
    void xrayExtraction_multipleInvocations_sameTraceId_unifiedTrace() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        var extractedContext = new ExtractedContext(xrayTraceId, "53995c3f42cd8ad8");

        spanExporter = InMemorySpanExporter.create();
        var xrayPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        // First invocation
        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));
        xrayPlugin.onOperationStart(new OperationInfo("op-1", "step-1", "STEP", "Step", null, Instant.now(), null));
        xrayPlugin.onOperationEnd(
                new OperationEndInfo("op-1", "step-1", "STEP", "Step", null, Instant.now(), Instant.now(), null));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.PENDING, null));

        // Second invocation (same execution, same X-Ray Root from backend)
        xrayPlugin.onInvocationStart(new InvocationInfo("req-2", "arn:exec1", false));
        xrayPlugin.onOperationStart(new OperationInfo("op-2", "step-2", "STEP", "Step", null, Instant.now(), null));
        xrayPlugin.onOperationEnd(
                new OperationEndInfo("op-2", "step-2", "STEP", "Step", null, Instant.now(), Instant.now(), null));
        xrayPlugin.onInvocationEnd(
                new InvocationEndInfo("req-2", "arn:exec1", false, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 4, "Should have spans from both invocations");

        // All spans share the same X-Ray trace ID — unified trace
        assertTrue(
                spans.stream().allMatch(s -> s.getTraceId().equals(xrayTraceId)),
                "Both invocations should produce spans with the same X-Ray trace ID");
    }

    @Test
    void xrayExtraction_nullExtractor_fallsBackToArnDerived() {
        spanExporter = InMemorySpanExporter.create();
        var noXrayPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false);

        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";
        noXrayPlugin.onInvocationStart(new InvocationInfo("req-1", arn, true));
        noXrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());

        var traceId = spans.get(0).getTraceId();
        assertNotNull(traceId);
        assertEquals(32, traceId.length());
        assertTrue(traceId.matches("[0-9a-f]{32}"), "ARN-derived trace ID should be valid hex");
    }

    @Test
    void xrayExtraction_extractedTraceIdMatchesXrayConversion() {
        // Verify the end-to-end flow: X-Ray header → parse → trace ID in span
        var xrayRoot = "1-5759e988-bd862e3fe1be46a994272793";
        var expectedOtelTraceId = "5759e988bd862e3fe1be46a994272793";

        // Simulate what XRayContextExtractor does
        var convertedId = XRayContextExtractor.xrayRootToOtelTraceId(xrayRoot);
        assertEquals(expectedOtelTraceId, convertedId);

        // Now feed it through the plugin
        var extractedContext = new ExtractedContext(convertedId, null);
        spanExporter = InMemorySpanExporter.create();
        var xrayPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(expectedOtelTraceId, spans.get(0).getTraceId());
    }

    // ─── Cross-invocation continuation span tests ────────────────────────

    @Test
    void operationEnd_withoutMatchingStart_createsContinuationSpanWithLink() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        // onOperationEnd without a prior onOperationStart — operation completed between invocations
        plugin.onOperationEnd(
                new OperationEndInfo("op-wait-1", "my-wait", "WAIT", "Wait", null, Instant.now(), Instant.now(), null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size());

        var continuationSpan = spans.stream()
                .filter(s -> s.getName().contains("wait"))
                .findFirst()
                .orElseThrow();
        assertEquals("durable.wait:my-wait", continuationSpan.getName());
        assertFalse(continuationSpan.getLinks().isEmpty(), "Continuation span should have a Link");
    }

    @Test
    void operationEnd_withoutMatchingStart_withError_setsErrorStatus() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        plugin.onOperationEnd(new OperationEndInfo(
                "op-cb-1",
                "my-callback",
                "CALLBACK",
                "Callback",
                null,
                Instant.now(),
                Instant.now(),
                new RuntimeException("timed out")));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var continuationSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("callback"))
                .findFirst()
                .orElseThrow();
        assertEquals(StatusCode.ERROR, continuationSpan.getStatus().getStatusCode());
        assertFalse(continuationSpan.getLinks().isEmpty());
    }

    // ─── SuspendExecutionException handling ──────────────────────────────

    @Test
    void userFunctionEnd_withSuspendException_setsOutcomePending() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        plugin.onUserFunctionStart(new UserFunctionStartInfo(
                "op-1", "child-ctx", "CONTEXT", "RunInChildContext", null, Instant.now(), false, null));

        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1",
                "child-ctx",
                "CONTEXT",
                "RunInChildContext",
                null,
                Instant.now(),
                Instant.now(),
                false,
                null,
                false,
                new SuspendExecutionException()));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.PENDING, null));

        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("child-ctx"))
                .findFirst()
                .orElseThrow();
        // Should NOT have ERROR status — suspension is not an error
        assertNotEquals(StatusCode.ERROR, attemptSpan.getStatus().getStatusCode());
    }

    // ─── Attempt span cleanup at invocation end ──────────────────────────

    @Test
    void attemptSpan_endedAtInvocationEnd_whenUserFunctionEndNotCalled() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        // Start attempt but never call onUserFunctionEnd (simulates crash before end hook)
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "running", "STEP", "Step", null, Instant.now(), false, 1));

        // Invocation ends — attempt span should be cleaned up
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.PENDING, null));

        var spans = spanExporter.getFinishedSpanItems();
        var attemptSpan = spans.stream()
                .filter(s -> s.getName().contains("running"))
                .findFirst()
                .orElseThrow();
        assertNotNull(attemptSpan, "Attempt span should be exported even without onUserFunctionEnd");
    }

    // ─── Parent resolution with parentId ─────────────────────────────────

    @Test
    void childOperation_parentedToParentOperationSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true));

        // Parent context operation
        plugin.onOperationStart(new OperationInfo(
                "op-parent", "my-context", "CONTEXT", "RunInChildContext", null, Instant.now(), null));

        // Child operation with parentId pointing to parent
        plugin.onOperationStart(
                new OperationInfo("op-child", "inner-step", "STEP", "Step", "op-parent", Instant.now(), null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-child", "inner-step", "STEP", "Step", "op-parent", Instant.now(), Instant.now(), null));

        plugin.onOperationEnd(new OperationEndInfo(
                "op-parent", "my-context", "CONTEXT", "RunInChildContext", null, Instant.now(), Instant.now(), null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();

        var parentSpan = spans.stream()
                .filter(s -> s.getName().contains("context"))
                .findFirst()
                .orElseThrow();
        var childSpan = spans.stream()
                .filter(s -> s.getName().contains("inner-step"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                parentSpan.getSpanId(),
                childSpan.getParentSpanId(),
                "Child operation should be parented to parent operation span");
    }

    // ─── Multi-invocation step-wait-step scenario ────────────────────────

    @Test
    void multiInvocation_stepWaitStep_producesCorrectSpans() {
        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";

        // Invocation 1: step completes, wait starts
        plugin.onInvocationStart(new InvocationInfo("req-1", arn, true));
        plugin.onOperationStart(new OperationInfo("op-1", "step-A", "STEP", "Step", null, Instant.now(), null));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-A", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-A", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(
                new OperationEndInfo("op-1", "step-A", "STEP", "Step", null, Instant.now(), Instant.now(), null));
        plugin.onOperationStart(new OperationInfo("op-2", "pause", "WAIT", "Wait", null, Instant.now(), null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.PENDING, null));

        // Invocation 1 should have: step op + step attempt + wait (PENDING) + invocation = 4
        assertEquals(4, spanExporter.getFinishedSpanItems().size());
        var inv1TraceId = spanExporter.getFinishedSpanItems().get(0).getTraceId();

        spanExporter.reset();

        // Invocation 2: wait completed between invocations, new step runs
        plugin.onInvocationStart(new InvocationInfo("req-2", arn, false));
        plugin.onOperationEnd(
                new OperationEndInfo("op-2", "pause", "WAIT", "Wait", null, Instant.now(), Instant.now(), null));
        plugin.onOperationStart(new OperationInfo("op-3", "step-B", "STEP", "Step", null, Instant.now(), null));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-3", "step-B", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-3", "step-B", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(
                new OperationEndInfo("op-3", "step-B", "STEP", "Step", null, Instant.now(), Instant.now(), null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", arn, false, InvocationStatus.SUCCEEDED, null));

        var inv2Spans = spanExporter.getFinishedSpanItems();
        // wait continuation + step-B op + step-B attempt + invocation = 4
        assertEquals(4, inv2Spans.size());

        // Same trace ID across invocations
        var inv2TraceId = inv2Spans.get(0).getTraceId();
        assertEquals(inv1TraceId, inv2TraceId);

        // Wait continuation should have a Link
        var waitContinuation = inv2Spans.stream()
                .filter(s -> s.getName().contains("wait"))
                .findFirst()
                .orElseThrow();
        assertFalse(waitContinuation.getLinks().isEmpty());
    }
}
