// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.plugin.*;

class ExecutionOtelPluginTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";

    private InMemorySpanExporter spanExporter;
    private ExecutionOtelPlugin plugin;

    @BeforeEach
    void setUp() {
        DeterministicIdGenerator.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        spanExporter = InMemorySpanExporter.create();
        plugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false,
                "Workflow");
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    // ─── Default constructor ─────────────────────────────────────────────

    @Test
    void defaultConstructor_throwsWhenAutoConfigurationCustomizerProviderIsNotInstalled() {
        GlobalOpenTelemetry.resetForTest();
        var error = assertThrows(IllegalStateException.class, ExecutionOtelPlugin::new);
        assertTrue(error.getMessage().contains("OtelPluginAutoConfigurationCustomizerProvider"));
    }

    @Test
    void defaultConstructor_usesGlobalSdkTracerProviderDirectly() {
        OtelPluginAutoConfigurationState.markInstalled();
        GlobalOpenTelemetry.resetForTest();
        var globalExporter = InMemorySpanExporter.create();
        var globalTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(globalExporter))
                .build();
        OpenTelemetrySdk.builder().setTracerProvider(globalTracerProvider).buildAndRegisterGlobal();

        var defaultPlugin = new ExecutionOtelPlugin();
        defaultPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        defaultPlugin.onOperationStart(
                new OperationInfo("op-1", "step", "STEP", "Step", null, Instant.now(), null, false));
        defaultPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        defaultPlugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = globalExporter.getFinishedSpanItems();
        // Workflow + Invocation + operation = 3
        assertEquals(3, spans.size());
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("Workflow")));
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("Invocation")));
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("step")));
    }

    // ─── Workflow root span lifecycle ────────────────────────────────────

    @Test
    void terminalInvocation_exportsWorkflowAndInvocationSpans() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size(), "Terminal invocation should export the Workflow span and the invocation span");

        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");

        assertEquals(StatusCode.OK, workflowSpan.getStatus().getStatusCode());
        assertEquals(StatusCode.OK, invocationSpan.getStatus().getStatusCode());
    }

    @Test
    void spans_carryWorkflowServiceName() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var workflowSpan = spanByName(spanExporter.getFinishedSpanItems(), "Workflow");
        assertEquals(
                "workflow",
                workflowSpan
                        .getResource()
                        .getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.name")),
                "Spans should carry service.name=workflow");
    }

    @Test
    void workflowSpan_startsAtExecutionStartTime() {
        var start = Instant.parse("2026-01-15T08:00:00Z");
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, start));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var workflowSpan = spanByName(spanExporter.getFinishedSpanItems(), "Workflow");
        assertEquals(
                start.toEpochMilli(),
                workflowSpan.getStartEpochNanos() / 1_000_000,
                "Workflow span should start at the execution start time from InvocationInfo");
    }

    @Test
    void workflowSpan_hasInternalKind() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        assertEquals(
                SpanKind.INTERNAL,
                spanByName(spanExporter.getFinishedSpanItems(), "Workflow").getKind(),
                "Workflow span must be INTERNAL kind");
    }

    @Test
    void workflowSpan_isRoot_invocationSpanIsChild() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");

        assertFalse(
                invocationSpan.getParentSpanContext().getSpanId().equals("0000000000000000"),
                "Invocation span must have a parent");
        assertEquals(
                workflowSpan.getSpanId(),
                invocationSpan.getParentSpanId(),
                "Invocation span must be a child of the Workflow root span");
        assertEquals(SpanKind.INTERNAL, invocationSpan.getKind());
    }

    @Test
    void nonTerminalInvocation_doesNotExportWorkflowSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));

        var spans = spanExporter.getFinishedSpanItems();
        // Only the invocation span is exported; the Workflow span is not ended on non-terminal status.
        assertEquals(1, spans.size());
        assertEquals("Invocation", spans.get(0).getName());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode(), "PENDING invocation span maps to OK");
    }

    @Test
    void workflowSpan_exportedOnceAcrossInvocations_sameSpanId() {
        // Invocation 1: non-terminal → no Workflow span exported
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));
        assertTrue(
                spanExporter.getFinishedSpanItems().stream()
                        .noneMatch(s -> s.getName().equals("Workflow")),
                "Workflow span must not be exported on a non-terminal invocation");
        spanExporter.reset();

        // Invocation 2: terminal → Workflow span exported with the deterministic ID
        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));

        var workflowSpan = spanByName(spanExporter.getFinishedSpanItems(), "Workflow");
        assertEquals(StatusCode.OK, workflowSpan.getStatus().getStatusCode());
        assertTrue(workflowSpan.getSpanId().matches("[0-9a-f]{16}"));
    }

    // ─── Status mapping ──────────────────────────────────────────────────

    @Test
    void failedInvocation_setsErrorOnBothWorkflowAndInvocationSpans() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(
                new InvocationEndInfo("req-1", ARN, true, InvocationStatus.FAILED, new RuntimeException("boom")));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(StatusCode.ERROR, spanByName(spans, "Workflow").getStatus().getStatusCode());
        assertEquals(
                StatusCode.ERROR, spanByName(spans, "Invocation").getStatus().getStatusCode());
    }

    @Test
    void retryingInvocation_invocationSpanUnset_workflowNotExported() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req-1", ARN, true, InvocationStatus.RETRYING, new RuntimeException("transient")));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "RETRYING is non-terminal — Workflow span not exported");
        var invocationSpan = spans.get(0);
        assertEquals("Invocation", invocationSpan.getName());
        assertEquals(
                StatusCode.UNSET,
                invocationSpan.getStatus().getStatusCode(),
                "RETRYING invocation span is UNSET (interface cannot distinguish STOPPED/TIMED_OUT)");
    }

    // ─── Operation topology: parented to Workflow, linked to invocation ──

    @Test
    void operationSpan_carriesAttemptNumberAtEnd() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "flaky", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "flaky", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", 3, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "flaky");
        assertEquals(
                3L,
                operationSpan
                        .getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.longKey("durable.attempt.number")),
                "Operation span should carry total attempt count at end");
    }

    @Test
    void continuationOperationSpan_carriesAttemptNumber() {
        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, Instant.now()));
        // No matching onOperationStart in this invocation — continuation branch.
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "flaky", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", 2, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "flaky");
        assertEquals(
                2L,
                operationSpan
                        .getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.longKey("durable.attempt.number")),
                "Continuation operation span should also carry the total attempt count");
    }

    @Test
    void operationSpan_startsAtOperationStartTimestamp() {
        var opStart = Instant.parse("2026-02-01T10:00:00Z");
        var opEnd = Instant.parse("2026-02-01T10:00:03Z");
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "step-a", "STEP", "Step", null, opStart, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, opStart, opEnd, "SUCCEEDED", null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "step-a");
        assertEquals(
                opStart.toEpochMilli(),
                operationSpan.getStartEpochNanos() / 1_000_000,
                "Operation span should start at OperationInfo.startTimestamp()");
    }

    @Test
    void operationSpan_parentedToWorkflow_linkedToInvocation() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");
        var operationSpan = spanByName(spans, "step-a");

        assertEquals(
                workflowSpan.getSpanId(),
                operationSpan.getParentSpanId(),
                "Operation span must be parented to the Workflow span, not the invocation span");
        assertTrue(
                operationSpan.getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(invocationSpan.getSpanId())),
                "Operation span must carry a link to the invocation span");
    }

    @Test
    void attemptSpan_childOfOperation_linkedToInvocation() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "compute", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "compute", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "compute", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "compute", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var operationSpan = spanByName(spans, "compute");
        var invocationSpan = spanByName(spans, "Invocation");
        var attemptSpan = spans.stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();

        assertEquals("compute attempt 1", attemptSpan.getName());
        assertEquals(
                operationSpan.getSpanId(),
                attemptSpan.getParentSpanId(),
                "Attempt span must be a child of its operation span");
        assertTrue(
                attemptSpan.getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(invocationSpan.getSpanId())),
                "Attempt span must carry a link to the invocation span");
    }

    @Test
    void childOperation_parentedToParentOperationSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo(
                "op-parent", "my-context", "CONTEXT", "RunInChildContext", null, Instant.now(), null, false));
        plugin.onOperationStart(
                new OperationInfo("op-child", "inner-step", "STEP", "Step", "op-parent", Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-child",
                "inner-step",
                "STEP",
                "Step",
                "op-parent",
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-parent",
                "my-context",
                "CONTEXT",
                "RunInChildContext",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var parentSpan = spanByName(spans, "my-context");
        var childSpan = spanByName(spans, "inner-step");
        assertEquals(
                parentSpan.getSpanId(),
                childSpan.getParentSpanId(),
                "Child operation should be parented to its parent operation span");
    }

    // ─── Failure propagation ─────────────────────────────────────────────

    @Test
    void userFunctionFailure_setsErrorOnAttemptSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
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
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.FAILED, null));

        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        assertEquals(StatusCode.ERROR, attemptSpan.getStatus().getStatusCode());
    }

    @Test
    void userFunctionSuccess_setsOkOnAttemptSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "compute", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "compute", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        assertEquals(StatusCode.OK, attemptSpan.getStatus().getStatusCode());
    }

    @Test
    void operationSuccess_setsOkOnOperationSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "step-ok", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-ok", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "step-ok");
        assertEquals(StatusCode.OK, operationSpan.getStatus().getStatusCode());
    }

    @Test
    void operationEnd_withNonSuccessStatusAndNoError_leavesOperationSpanUnset() {
        // onOperationEnd fires for every terminal status. A CANCELLED operation (or an error-less
        // FAILED/TIMED_OUT/STOPPED) carries a non-null, non-SUCCEEDED status with a null error. It must NOT be
        // stamped OK — the span status stays UNSET.
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-cancel", "step-cancel", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-cancel",
                "step-cancel",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "CANCELLED",
                null,
                false,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "step-cancel");
        assertEquals(StatusCode.UNSET, operationSpan.getStatus().getStatusCode());
    }

    @Test
    void operationEnd_withoutStart_nonSuccessStatusAndNoError_leavesContinuationSpanUnset() {
        // Same guard on the continuation-span branch (operation completed between invocations): an error-less
        // TIMED_OUT terminal status must NOT be stamped OK.
        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, Instant.now()));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-cb-timeout",
                "my-callback",
                "CALLBACK",
                "Callback",
                null,
                Instant.now(),
                Instant.now(),
                "TIMED_OUT",
                null,
                false,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));

        var continuationSpan = spanByName(spanExporter.getFinishedSpanItems(), "my-callback");
        assertEquals(StatusCode.UNSET, continuationSpan.getStatus().getStatusCode());
    }

    @Test
    void operationEnd_withNullStatusAndNoError_setsOkOnOperationSpan() {
        // A successful statusless virtual (FLAT CONTEXT) operation fires onOperationEnd with a null operation ->
        // null status and null error. This is genuine success and must be stamped OK.
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-ctx", "my-ctx", "CONTEXT", null, null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-ctx", "my-ctx", "CONTEXT", null, null, Instant.now(), Instant.now(), null, null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "my-ctx");
        assertEquals(StatusCode.OK, operationSpan.getStatus().getStatusCode());
    }

    @Test
    void operationNotCompleted_notEndedAtInvocationEnd() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), null, false));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));

        var spans = spanExporter.getFinishedSpanItems();
        // Only the invocation span is exported. The still-open operation span is NOT force-ended (no PENDING
        // span here), and the Workflow span is not exported on a non-terminal invocation.
        assertEquals(1, spans.size());
        assertEquals("Invocation", spans.get(0).getName());
        assertTrue(
                spans.stream().noneMatch(s -> s.getName().equals("my-wait")),
                "An operation still open at invocation end must not be ended/exported in onInvocationEnd");
    }

    @Test
    void operationOpenedThenCompletedNextInvocation_exportedOnceOnOperationEnd() {
        // Invocation 1: operation opens but does not complete.
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), null, false));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));
        assertTrue(
                spanExporter.getFinishedSpanItems().stream()
                        .noneMatch(s -> s.getName().equals("my-wait")),
                "Operation span must not be exported by the suspending invocation");
        spanExporter.reset();

        // Invocation 2: the operation completes → materialized once via onOperationEnd, linked to this invocation.
        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, Instant.now()));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var waitSpans =
                spans.stream().filter(s -> s.getName().equals("my-wait")).toList();
        assertEquals(1, waitSpans.size(), "The operation must be exported exactly once, when it completes");
        var invocationSpan = spanByName(spans, "Invocation");
        assertTrue(
                waitSpans.get(0).getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(invocationSpan.getSpanId())),
                "Completed operation span should link to the invocation that completed it");
    }

    // ─── Cross-invocation stitching ──────────────────────────────────────

    @Test
    void allSpansShareTraceId_acrossInvocations() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "step-1", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-1", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));
        var firstTraceId = spanExporter.getFinishedSpanItems().get(0).getTraceId();
        spanExporter.reset();

        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));
        var secondSpans = spanExporter.getFinishedSpanItems();

        assertTrue(
                secondSpans.stream().allMatch(s -> s.getTraceId().equals(firstTraceId)),
                "All spans of one execution must share the same trace ID");
    }

    @Test
    void operationEnd_withoutStart_createsContinuationSpanWithLink() {
        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, Instant.now()));
        // Operation completed between invocations — no matching onOperationStart in this invocation.
        plugin.onOperationEnd(new OperationEndInfo(
                "op-wait-1",
                "my-wait",
                "WAIT",
                "Wait",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var continuationSpan = spanByName(spans, "my-wait");
        var invocationSpan = spanByName(spans, "Invocation");
        assertFalse(continuationSpan.getLinks().isEmpty(), "Continuation span should have a link");
        assertTrue(
                continuationSpan.getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(invocationSpan.getSpanId())),
                "Continuation span should link to the current invocation span");
    }

    @Test
    void deterministicWorkflowSpanId_stableAcrossInvocations() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));
        var firstWorkflowSpanId =
                spanByName(spanExporter.getFinishedSpanItems(), "Workflow").getSpanId();
        spanExporter.reset();

        // A second (independent) plugin for the same execution ARN must derive the same Workflow span ID.
        var exporter2 = InMemorySpanExporter.create();
        var plugin2 = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter2)),
                () -> null,
                false,
                "Workflow");
        plugin2.onInvocationStart(new InvocationInfo("req-9", ARN, true, Instant.now()));
        plugin2.onInvocationEnd(new InvocationEndInfo("req-9", ARN, true, InvocationStatus.SUCCEEDED, null));
        var secondWorkflowSpanId =
                spanByName(exporter2.getFinishedSpanItems(), "Workflow").getSpanId();

        assertEquals(
                firstWorkflowSpanId,
                secondWorkflowSpanId,
                "Workflow span ID must be deterministic for a given execution ARN");
    }

    // ─── Sampling ────────────────────────────────────────────────────────

    @Test
    void sampling_disabled_producesNoSpans() {
        var exporter = InMemorySpanExporter.create();
        var sampledPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                () -> null,
                false,
                "Workflow");
        sampledPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        sampledPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));
        assertTrue(exporter.getFinishedSpanItems().isEmpty(), "No spans should be exported with 0% sampling");
    }

    // ─── X-Ray trace ID ──────────────────────────────────────────────────

    @Test
    void xrayExtraction_allSpansShareExtractedTraceId() {
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var exporter = InMemorySpanExporter.create();
        var xrayPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                () -> new ExtractedContext(xrayTraceId, null),
                false,
                "Workflow");
        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        xrayPlugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, false));
        xrayPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = exporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 3, "Workflow + invocation + operation spans expected");
        assertTrue(
                spans.stream().allMatch(s -> s.getTraceId().equals(xrayTraceId)),
                "All spans must share the extracted X-Ray trace ID");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static io.opentelemetry.sdk.trace.data.SpanData spanByName(
            java.util.List<io.opentelemetry.sdk.trace.data.SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span named '" + name + "' in "
                        + spans.stream()
                                .map(io.opentelemetry.sdk.trace.data.SpanData::getName)
                                .toList()));
    }
}
