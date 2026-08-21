// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.plugin.*;

class ExecutionOtelPluginTest {

    private static final String ARN = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final String CONFIGURED_SERVICE_NAME = "durable-execution-conformance";

    private InMemorySpanExporter spanExporter;
    private ExecutionOtelPlugin plugin;

    @BeforeEach
    void setUp() {
        DeterministicIdGenerator.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        spanExporter = InMemorySpanExporter.create();
        var resource = Resource.create(Attributes.of(SERVICE_NAME, CONFIGURED_SERVICE_NAME));
        plugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    // ─── Default constructor ─────────────────────────────────────────────

    @Test
    void customInstrumentationName_isUsedForTracerScope() {
        var exporter = InMemorySpanExporter.create();
        var customPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .instrumentationName("my-custom-scope")
                        .build());
        customPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        customPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = exporter.getFinishedSpanItems();
        assertFalse(spans.isEmpty());
        for (var span : spans) {
            assertEquals("my-custom-scope", span.getInstrumentationScopeInfo().getName());
        }
    }

    @Test
    void defaultConstructor_retriesGlobalProviderBindingOnNextInvocation() {
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();

        var defaultPlugin = new ExecutionOtelPlugin();
        defaultPlugin.onInvocationStart(new InvocationInfo("req-disabled", "arn:disabled", true, Instant.now()));
        defaultPlugin.onOperationStart(new OperationInfo(
                "op-disabled", "disabled-step", "STEP", "Step", null, Instant.now(), null, null, false));
        defaultPlugin.onOperationEnd(new OperationEndInfo(
                "op-disabled",
                "disabled-step",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
        defaultPlugin.onInvocationEnd(
                new InvocationEndInfo("req-disabled", "arn:disabled", true, InvocationStatus.SUCCEEDED, null));

        assertFalse(GlobalOpenTelemetry.isSet(), "An unavailable provider must not install the no-op global");

        var globalExporter = InMemorySpanExporter.create();
        var globalTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(globalExporter))
                .build();
        OpenTelemetrySdk.builder().setTracerProvider(globalTracerProvider).buildAndRegisterGlobal();

        defaultPlugin.onInvocationStart(new InvocationInfo("req-enabled", "arn:enabled", true, Instant.now()));
        defaultPlugin.onOperationStart(new OperationInfo(
                "op-enabled", "enabled-step", "STEP", "Step", null, Instant.now(), null, null, false));
        defaultPlugin.onOperationEnd(new OperationEndInfo(
                "op-enabled",
                "enabled-step",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
        defaultPlugin.onInvocationEnd(
                new InvocationEndInfo("req-enabled", "arn:enabled", true, InvocationStatus.SUCCEEDED, null));

        var spans = globalExporter.getFinishedSpanItems();
        assertEquals(3, spans.size());
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("enabled-step")));
        assertFalse(spans.stream().anyMatch(span -> span.getName().equals("disabled-step")));
    }

    @Test
    void defaultConstructor_usesGlobalSdkTracerProviderDirectly() {
        var defaultPlugin = new ExecutionOtelPlugin();
        assertFalse(GlobalOpenTelemetry.isSet());

        OtelPluginAutoConfigurationState.markInstalled();
        var globalExporter = InMemorySpanExporter.create();
        var globalTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(globalExporter))
                .build();
        OpenTelemetrySdk.builder().setTracerProvider(globalTracerProvider).buildAndRegisterGlobal();

        defaultPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        defaultPlugin.onOperationStart(
                new OperationInfo("op-1", "step", "STEP", "Step", null, Instant.now(), null, null, false));
        defaultPlugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "step",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
        defaultPlugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = globalExporter.getFinishedSpanItems();
        // Workflow + Invocation + operation = 3
        assertEquals(3, spans.size());
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("Workflow")));
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("Invocation")));
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("step")));
    }

    @Test
    void executionOtelPluginProvider_isRegisteredAsServiceProvider() {
        var provider = ServiceLoader.load(DurableExecutionPluginProvider.class).stream()
                .filter(candidate -> candidate.type().equals(ExecutionOtelPluginProvider.class))
                .findFirst()
                .orElseThrow()
                .get();

        assertEquals("otel-execution", provider.getName());
        assertEquals(DurableExecutionPluginProvider.API_VERSION, provider.getApiVersion());
        assertEquals(ExecutionOtelPlugin.class, provider.getPluginType());
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
    void spans_preserveConfiguredServiceName() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        for (var span : spanExporter.getFinishedSpanItems()) {
            assertEquals(
                    CONFIGURED_SERVICE_NAME,
                    span.getResource().getAttribute(SERVICE_NAME),
                    "Plugin must preserve the caller-configured service.name");
        }
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
                "Workflow span should start at the execution start time captured from InvocationInfo");
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
    void workflowAndInvocationSpans_areIndependentRoots_withoutAmbientContext() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");

        assertFalse(workflowSpan.getParentSpanContext().isValid(), "Workflow span must be a root");
        assertFalse(invocationSpan.getParentSpanContext().isValid(), "Invocation span must be a root");
        assertNotEquals(
                workflowSpan.getTraceId(), invocationSpan.getTraceId(), "Independent roots must not share a trace ID");
        assertEquals(SpanKind.INTERNAL, invocationSpan.getKind());
    }

    @Test
    void invocationStart_usesCurrentSpanContext_whenExtractorReturnsNull() {
        var traceId = "5759e988bd862e3fe1be46a994272793";
        var parentSpanId = "53995c3f42cd8ad8";
        var parentSpanContext =
                SpanContext.create(traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault());

        try (var ignored = Span.wrap(parentSpanContext).makeCurrent()) {
            plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        }
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var invocationSpan = spanByName(spanExporter.getFinishedSpanItems(), "Invocation");
        assertEquals(traceId, invocationSpan.getTraceId());
        assertEquals(parentSpanId, invocationSpan.getParentSpanId());
    }

    @Test
    void nonTerminalInvocation_doesNotExportWorkflowSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));

        var spans = spanExporter.getFinishedSpanItems();
        // Only the invocation span is exported. On a non-terminal status the Workflow span is never materialized (no
        // recording span is created until the terminal invocation), so there is nothing to export or abandon.
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
        plugin.onOperationStart(
                new OperationInfo("op-1", "flaky", "STEP", "Step", null, Instant.now(), null, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "flaky",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                3,
                false,
                null,
                null));
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
                "op-1",
                "flaky",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                2,
                false,
                null,
                null));
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
        plugin.onOperationStart(new OperationInfo("op-1", "step-a", "STEP", "Step", null, opStart, null, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, opStart, opEnd, "SUCCEEDED", null, false, null, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "step-a");
        assertEquals(
                opStart.toEpochMilli(),
                operationSpan.getStartEpochNanos() / 1_000_000,
                "Operation span should start at OperationInfo.startTimestamp()");
    }

    @Test
    void virtualOperation_spanStartsAtOnOperationStartTimestamp() {
        // FLAT map/parallel child contexts fire onOperationStart with a real start time, but onOperationEnd with null
        // start/end timestamps (the SDK converter maps a null Operation to null end timestamps). Because the span is
        // materialized at onOperationEnd, it must fall back to the start captured at onOperationStart — otherwise it
        // would start at materialization time (after the child code ran), giving a near-zero or inverted duration.
        var opStart = Instant.parse("2026-03-01T12:00:00Z");
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-flat", "my-map", "CONTEXT", "Map", null, opStart, null, null, false));
        // Virtual end: null start, null end, null status (operation == null in PluginInfoConverter).
        plugin.onOperationEnd(new OperationEndInfo(
                "op-flat", "my-map", "CONTEXT", "Map", null, null, null, null, null, false, null, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var span = spanByName(spanExporter.getFinishedSpanItems(), "my-map");
        assertEquals(
                opStart.toEpochMilli(),
                span.getStartEpochNanos() / 1_000_000,
                "Virtual operation span must start at the timestamp captured in onOperationStart, not at "
                        + "span materialization time");
        assertTrue(
                span.getEndEpochNanos() >= span.getStartEpochNanos(),
                "Virtual operation span must not end before it starts");
    }

    @Test
    void operationSpan_parentedToWorkflow_linkedToInvocation() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "step-a",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
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
        plugin.onOperationStart(
                new OperationInfo("op-1", "compute", "STEP", "Step", null, Instant.now(), null, null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "compute", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "compute", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "compute",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
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
    void attemptSpan_carriesOperationSubtype() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "process-order", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "process-order", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("process-order"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "Step",
                attemptSpan.getAttributes().get(AttributeKey.stringKey("durable.operation.subtype")),
                "Attempt span should carry durable.operation.subtype from the operation");
    }

    @Test
    void childOperation_parentedToParentOperationSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo(
                "op-parent", "my-context", "CONTEXT", "RunInChildContext", null, Instant.now(), null, null, false));
        plugin.onOperationStart(new OperationInfo(
                "op-child", "inner-step", "STEP", "Step", "op-parent", Instant.now(), null, null, false));
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
                null,
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
                null,
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
        plugin.onOperationStart(
                new OperationInfo("op-1", "step-ok", "STEP", "Step", null, Instant.now(), null, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "step-ok",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
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
                new OperationInfo("op-cancel", "step-cancel", "STEP", "Step", null, Instant.now(), null, null, false));
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
                null,
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
                null,
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
                new OperationInfo("op-ctx", "my-ctx", "CONTEXT", null, null, Instant.now(), null, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-ctx",
                "my-ctx",
                "CONTEXT",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null,
                null,
                false,
                null,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanByName(spanExporter.getFinishedSpanItems(), "my-ctx");
        assertEquals(StatusCode.OK, operationSpan.getStatus().getStatusCode());
    }

    @Test
    void operationNotCompleted_notMaterializedAtInvocationEnd() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), null, null, false));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));

        var spans = spanExporter.getFinishedSpanItems();
        // Only the invocation span is exported. An operation that suspends before completing is never materialized as
        // a recording span (onOperationStart retains only its deterministic context); it is created and ended once,
        // later, in onOperationEnd. So there is no open operation span to abandon here, and the Workflow span is not
        // materialized on a non-terminal invocation either.
        assertEquals(1, spans.size());
        assertEquals("Invocation", spans.get(0).getName());
        assertTrue(
                spans.stream().noneMatch(s -> s.getName().equals("my-wait")),
                "An operation still open at invocation end must not be materialized/exported in onInvocationEnd");
    }

    @Test
    void operationOpenedThenCompletedNextInvocation_exportedOnceOnOperationEnd() {
        // Invocation 1: operation opens but does not complete.
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), null, null, false));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));
        assertTrue(
                spanExporter.getFinishedSpanItems().stream()
                        .noneMatch(s -> s.getName().equals("my-wait")),
                "Operation span must not be exported by the suspending invocation");
        spanExporter.reset();

        // Invocation 2: the operation completes → materialized once via onOperationEnd, linked to this invocation.
        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, Instant.now()));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "my-wait",
                "WAIT",
                "Wait",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
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
    void workflowTraceIsStableAndInvocationRootsAreFresh_acrossInvocations() {
        var executionStartTime = Instant.parse("2026-08-15T00:00:00Z");
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, executionStartTime));
        plugin.onOperationStart(
                new OperationInfo("op-1", "step-1", "STEP", "Step", null, Instant.now(), null, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "step-1",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));
        var firstSpans = spanExporter.getFinishedSpanItems();
        var workflowTraceId = spanByName(firstSpans, "step-1").getTraceId();
        var firstInvocationTraceId = spanByName(firstSpans, "Invocation").getTraceId();
        spanExporter.reset();

        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, executionStartTime));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));
        var secondSpans = spanExporter.getFinishedSpanItems();
        var workflowSpan = spanByName(secondSpans, "Workflow");
        var secondInvocationSpan = spanByName(secondSpans, "Invocation");

        assertEquals(workflowTraceId, workflowSpan.getTraceId());
        assertNotEquals(workflowTraceId, firstInvocationTraceId);
        assertNotEquals(workflowTraceId, secondInvocationSpan.getTraceId());
        assertNotEquals(firstInvocationTraceId, secondInvocationSpan.getTraceId());
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
                null,
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
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());
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
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());
        sampledPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        sampledPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));
        assertTrue(exporter.getFinishedSpanItems().isEmpty(), "No spans should be exported with 0% sampling");
    }

    @Test
    void operationSampling_followsWorkflowRoot_notTheInvocationSpan() {
        // Operations and attempts belong to the Workflow trace, so their sampling follows the Workflow root rather than
        // the per-invocation ambient decision. Here the ambient parent is dropped by a parent-based sampler (so the
        // Invocation span is not recorded), yet the operation still belongs to the Workflow trace and is exported.
        // The operation's link to that unsampled invocation is left unresolved, which is the accepted trade-off.
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        var plugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.parentBased(
                                io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn()))
                        // An unsampled ambient parent: parentBased copies the parent's decision, so the Invocation span
                        // (and only it) is dropped.
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> new ExtractedContext(xrayTraceId, parentSpanId))
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());

        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "step-a",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                1,
                false,
                null,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = exporter.getFinishedSpanItems();
        var operationSpan = spanByName(spans, "step-a");
        var workflowSpan = spanByName(spans, "Workflow");
        assertEquals(
                workflowSpan.getTraceId(),
                operationSpan.getTraceId(),
                "The operation must live in the Workflow trace, independent of the invocation's ambient trace");
        assertEquals(
                workflowSpan.getSpanId(),
                operationSpan.getParentSpanId(),
                "The operation must be parented to the Workflow root, so it shares the Workflow root's fate");
    }

    @Test
    void rootRejectingSampler_dropsWorkflowTreeWithoutOrphans() {
        // The Workflow root is created with no parent, so a parent-based sampler applies its root rule to it. With a
        // rejecting root rule the Workflow span is dropped, and the operation/attempt spans parented to the Workflow
        // context must be dropped with it — otherwise they would export as orphans referencing a Workflow span that
        // never shipped. The ambient parent is sampled here, so this also proves the decision is taken from the
        // Workflow root rather than the invocation span.
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        var rejectingPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.parentBased(
                                io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff()))
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> new ExtractedContext(xrayTraceId, parentSpanId))
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());

        rejectingPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        rejectingPlugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, null, false));
        rejectingPlugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), false, 1));
        rejectingPlugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        rejectingPlugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "step-a",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                1,
                false,
                null,
                null));
        rejectingPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var workflowTreeSpans = exporter.getFinishedSpanItems().stream()
                .filter(s -> !s.getName().equals("Invocation"))
                .toList();
        assertTrue(
                workflowTreeSpans.isEmpty(),
                "A rejecting root sampler must drop the Workflow span and every operation/attempt parented to it, "
                        + "leaving no orphans; got "
                        + workflowTreeSpans.stream()
                                .map(io.opentelemetry.sdk.trace.data.SpanData::getName)
                                .toList());
    }

    // ─── X-Ray trace ID ──────────────────────────────────────────────────

    @Test
    void xrayExtraction_keepsWorkflowTraceIndependent() {
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        var xrayPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> new ExtractedContext(xrayTraceId, parentSpanId))
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());
        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        xrayPlugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, null, false));
        xrayPlugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "step-a",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null,
                null));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = exporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 3, "Workflow + invocation + operation spans expected");
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");
        var operationSpan = spanByName(spans, "step-a");
        assertEquals(xrayTraceId, invocationSpan.getTraceId());
        assertEquals(parentSpanId, invocationSpan.getParentSpanId());
        assertNotEquals(xrayTraceId, workflowSpan.getTraceId());
        assertEquals(workflowSpan.getTraceId(), operationSpan.getTraceId());
    }

    @Test
    void xrayExtraction_withParentSpanId_invocationSpanHasCorrectParent() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        var xrayPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> new ExtractedContext(xrayTraceId, parentSpanId))
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = exporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");
        assertFalse(workflowSpan.getParentSpanContext().isValid(), "Workflow span must remain an independent root");
        assertEquals(xrayTraceId, invocationSpan.getTraceId());
        assertEquals(parentSpanId, invocationSpan.getParentSpanId());
    }

    // ─── Span lifecycle: every recording span is ended exactly once ──────

    @Test
    void terminalSuccess_endsEveryRecordingSpanExactlyOnce() {
        assertEveryRecordingSpanEndedOnce(InvocationStatus.SUCCEEDED, null);
    }

    @Test
    void terminalFailure_endsEveryRecordingSpanExactlyOnce() {
        assertEveryRecordingSpanEndedOnce(InvocationStatus.FAILED, new RuntimeException("boom"));
    }

    @Test
    void pendingInvocation_endsEveryRecordingSpanExactlyOnce() {
        assertEveryRecordingSpanEndedOnce(InvocationStatus.PENDING, null);
    }

    @Test
    void retryingInvocation_endsEveryRecordingSpanExactlyOnce() {
        assertEveryRecordingSpanEndedOnce(InvocationStatus.RETRYING, new RuntimeException("transient"));
    }

    /**
     * Runs an invocation with a step operation and attempt, ending with the given status, and asserts every started
     * span was ended exactly once and none is left recording. Non-terminal statuses leave the operation open at
     * suspend.
     */
    private void assertEveryRecordingSpanEndedOnce(InvocationStatus status, Throwable error) {
        var counting = new CountingSpanProcessor();
        var lifecyclePlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(counting),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());

        var terminal = status == InvocationStatus.SUCCEEDED || status == InvocationStatus.FAILED;

        lifecyclePlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        lifecyclePlugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, null, false));
        lifecyclePlugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), false, 1));
        lifecyclePlugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        if (terminal) {
            // On a terminal invocation the operation completes; on a non-terminal one it stays open (suspends).
            lifecyclePlugin.onOperationEnd(new OperationEndInfo(
                    "op-1",
                    "step-a",
                    "STEP",
                    "Step",
                    null,
                    Instant.now(),
                    Instant.now(),
                    "SUCCEEDED",
                    1,
                    false,
                    null,
                    null));
        }
        lifecyclePlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, status, error));

        counting.assertBalancedAndNotRecording();
    }

    /**
     * A {@link io.opentelemetry.sdk.trace.SpanProcessor} that records every started span and counts ends, so a test can
     * prove that every recording span is ended exactly once and none is left recording.
     */
    private static final class CountingSpanProcessor implements io.opentelemetry.sdk.trace.SpanProcessor {
        private final java.util.List<io.opentelemetry.sdk.trace.ReadWriteSpan> started =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger ended = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void onStart(
                io.opentelemetry.context.Context parentContext, io.opentelemetry.sdk.trace.ReadWriteSpan span) {
            started.add(span);
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
            ended.incrementAndGet();
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        void assertBalancedAndNotRecording() {
            assertFalse(started.isEmpty(), "Expected the plugin to start at least one span");
            assertEquals(
                    started.size(), ended.get(), "Every recording span the plugin started must be ended exactly once");
            for (var span : started) {
                assertFalse(
                        span.isRecording(),
                        "No span may still be recording after onInvocationEnd (span '" + span.getName() + "')");
            }
        }
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
