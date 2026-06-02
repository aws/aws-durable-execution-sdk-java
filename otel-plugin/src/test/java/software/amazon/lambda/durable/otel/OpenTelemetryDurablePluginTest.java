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
import software.amazon.lambda.durable.plugin.*;

class OpenTelemetryDurablePluginTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetryDurablePlugin plugin;
    private DeterministicIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        idGenerator = new DeterministicIdGenerator();

        var tracerProvider = SdkTracerProvider.builder()
                .setIdGenerator(idGenerator)
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        plugin = new OpenTelemetryDurablePlugin(
                tracerProvider, idGenerator, () -> io.opentelemetry.context.Context.root(), 1.0, false);
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
    void sampling_disabledExecution_producesNoSpans() {
        spanExporter = InMemorySpanExporter.create();
        var sampledPlugin = new OpenTelemetryDurablePlugin(
                SdkTracerProvider.builder()
                        .setIdGenerator(idGenerator)
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                        .build(),
                idGenerator,
                () -> io.opentelemetry.context.Context.root(),
                0.0, // 0% sampling — nothing should be traced
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
}
