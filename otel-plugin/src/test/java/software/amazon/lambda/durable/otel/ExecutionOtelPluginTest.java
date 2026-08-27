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
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
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
        DurableSamplingDecision.clearSharedStateForTest();
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
        DurableSamplingDecision.clearSharedStateForTest();
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
    void workflowAndInvocationSpans_shareExecutionTrace_withoutAmbientContext() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");

        // With no propagated context, a synthetic execution root anchors the trace and both spans parent onto it.
        assertEquals(
                workflowSpan.getTraceId(),
                invocationSpan.getTraceId(),
                "Workflow and Invocation spans share the execution trace");
        assertTrue(workflowSpan.getParentSpanContext().isValid(), "Workflow span parents onto the execution ancestor");
        assertTrue(
                invocationSpan.getParentSpanContext().isValid(), "Invocation span parents onto the execution ancestor");
        assertEquals(
                workflowSpan.getParentSpanId(),
                invocationSpan.getParentSpanId(),
                "Both spans share the same synthetic execution root as parent");
        assertEquals(SpanKind.INTERNAL, invocationSpan.getKind());
    }

    @Test
    void invocationStart_joinsAmbientTrace_whenAmbientIsOnExecutionTrace() {
        // Drive an invocation to learn the canonical execution trace ID, then start a fresh invocation with an ambient
        // span on that same trace: the Invocation span joins the ambient span directly.
        plugin.onInvocationStart(new InvocationInfo("req-0", ARN, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-0", ARN, true, InvocationStatus.SUCCEEDED, null));
        var canonicalTraceId =
                spanByName(spanExporter.getFinishedSpanItems(), "Workflow").getTraceId();
        spanExporter.reset();

        var ambientSpanId = "53995c3f42cd8ad8";
        var ambient =
                SpanContext.create(canonicalTraceId, ambientSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        try (var ignored = Span.wrap(ambient).makeCurrent()) {
            plugin.onInvocationStart(new InvocationInfo("req-1", ARN, false, Instant.now()));
        }
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, false, InvocationStatus.SUCCEEDED, null));

        var invocationSpan = spanByName(spanExporter.getFinishedSpanItems(), "Invocation");
        assertEquals(canonicalTraceId, invocationSpan.getTraceId());
        assertEquals(ambientSpanId, invocationSpan.getParentSpanId(), "Invocation joins the ambient span on its trace");
    }

    @Test
    void invocationStart_staysOnExecutionTrace_withoutLinkingAmbientSpan() {
        // With no backend execution context (null extractor) but a valid ambient span on a different trace (for
        // example a per-invocation Lambda/agent span or a custom-propagated parent), the durable spans must stay on the
        // stable ARN-derived execution trace and must NOT link the ambient span: the conformance contract requires the
        // Invocation span to have no links, and the ambient span on a foreign trace is not modeled as a link.
        var ambientTraceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var ambientSpanId = "1111111111111111";
        var ambient =
                SpanContext.create(ambientTraceId, ambientSpanId, TraceFlags.getSampled(), TraceState.getDefault());
        try (var ignored = Span.wrap(ambient).makeCurrent()) {
            plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        }
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");
        assertNotEquals(ambientTraceId, workflowSpan.getTraceId(), "Workflow stays on the execution trace");
        assertEquals(workflowSpan.getTraceId(), invocationSpan.getTraceId(), "Invocation shares the execution trace");
        assertTrue(invocationSpan.getLinks().isEmpty(), "Invocation span carries no ambient link");
    }

    @Test
    void contextExtractor_isInvokedEveryInvocation_evenWithAmbientSpan_andBackendContextWins() {
        // Contract: the extractor is consulted on every invocation, unconditionally — including when a valid ambient
        // span is active — and a valid extracted backend context anchors the execution trace over the ambient span.
        var backendTraceId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var backendParentId = "2222222222222222";
        var extractCalls = new AtomicInteger();
        var exporter = InMemorySpanExporter.create();
        var extractorPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> {
                            extractCalls.incrementAndGet();
                            return new ExtractedContext(
                                    backendTraceId, backendParentId, ExtractedContext.Sampling.SAMPLED);
                        })
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());

        var ambient = SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "1111111111111111",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        try (var ignored = Span.wrap(ambient).makeCurrent()) {
            extractorPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        }
        extractorPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        assertEquals(1, extractCalls.get(), "Extractor is invoked even when a valid ambient span is active");
        var spans = exporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        assertEquals(backendTraceId, workflowSpan.getTraceId(), "Extracted backend context anchors the trace");
        assertEquals(backendParentId, workflowSpan.getParentSpanId(), "Workflow parents onto the backend span");
    }

    @Test
    void executionTrace_isStableAcrossReinvocations_withDifferentAmbientTraces() {
        // Reinvocation regression: the same durable execution keeps one trace ID across invocations even when the
        // ambient span differs on each invocation (as a per-invocation Lambda/agent span would).
        var ambientA = SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "1111111111111111",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        var ambientB = SpanContext.create(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "2222222222222222",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        var startTime = Instant.now();

        try (var ignored = Span.wrap(ambientA).makeCurrent()) {
            plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, startTime));
        }
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));
        var firstInvocationTrace =
                spanByName(spanExporter.getFinishedSpanItems(), "Invocation").getTraceId();
        spanExporter.reset();

        try (var ignored = Span.wrap(ambientB).makeCurrent()) {
            plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, startTime));
        }
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));
        var secondInvocationTrace =
                spanByName(spanExporter.getFinishedSpanItems(), "Invocation").getTraceId();

        assertEquals(
                firstInvocationTrace,
                secondInvocationTrace,
                "The execution trace is stable across reinvocations despite different ambient traces");
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
                "op-1",
                "compute",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                UserFunctionOutcome.SUCCEEDED,
                null));
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
                "op-1",
                "process-order",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                UserFunctionOutcome.SUCCEEDED,
                null));
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
                UserFunctionOutcome.FAILED,
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
                "op-1",
                "compute",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                UserFunctionOutcome.SUCCEEDED,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        assertEquals(StatusCode.OK, attemptSpan.getStatus().getStatusCode());
    }

    @Test
    void userFunctionIncomplete_leavesAttemptSpanUnset() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "waiting", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1",
                "waiting",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                UserFunctionOutcome.INCOMPLETE,
                new SuspendExecutionException()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.PENDING, null));

        var attemptSpan = spanByName(spanExporter.getFinishedSpanItems(), "waiting attempt 1");
        assertEquals(StatusCode.UNSET, attemptSpan.getStatus().getStatusCode());
        assertEquals("INCOMPLETE", attemptSpan.getAttributes().get(AttributeKey.stringKey("durable.attempt.outcome")));
        assertTrue(attemptSpan.getEvents().isEmpty());
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
    void operationNotCompleted_notEndedAtInvocationEnd() {
        plugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), null, null, false));
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
    void executionTraceIsStableAcrossInvocations_andSharedByInvocationSpans() {
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
        var executionTraceId = spanByName(firstSpans, "step-1").getTraceId();
        var firstInvocationTraceId = spanByName(firstSpans, "Invocation").getTraceId();
        spanExporter.reset();

        plugin.onInvocationStart(new InvocationInfo("req-2", ARN, false, executionStartTime));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", ARN, false, InvocationStatus.SUCCEEDED, null));
        var secondSpans = spanExporter.getFinishedSpanItems();
        var workflowSpan = spanByName(secondSpans, "Workflow");
        var secondInvocationSpan = spanByName(secondSpans, "Invocation");

        // The whole execution shares one trace ID, stable across invocations.
        assertEquals(executionTraceId, workflowSpan.getTraceId());
        assertEquals(executionTraceId, firstInvocationTraceId);
        assertEquals(executionTraceId, secondInvocationSpan.getTraceId());
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

    // ─── X-Ray trace ID ──────────────────────────────────────────────────

    @Test
    void xrayExtraction_undecidedSampling_remoteParentIsAncestor_flagUnset() {
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        // Two-arg context → UNDECIDED sampling: the valid remote parent is still the authoritative ancestor. A
        // non-parent-based alwaysOn sampler exports the spans so the topology is observable (a plain parent-based
        // sampler would drop them, since the remote parent's sampled flag is left unset).
        var xrayPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)),
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
        // The remote parent is the ancestor: Workflow and Invocation both parent onto it, on the remote trace.
        assertEquals(xrayTraceId, workflowSpan.getTraceId());
        assertEquals(xrayTraceId, invocationSpan.getTraceId());
        assertEquals(xrayTraceId, operationSpan.getTraceId());
        assertEquals(parentSpanId, workflowSpan.getParentSpanId(), "Workflow parents onto the remote span");
        assertEquals(parentSpanId, invocationSpan.getParentSpanId(), "Invocation parents onto the remote span");
        assertTrue(workflowSpan.getLinks().isEmpty(), "No remote-parent link when the remote context is the ancestor");
    }

    @Test
    void xrayExtraction_undecidedSampling_parentBasedSampler_defersToSamplerAndExports() {
        // With a ParentBased(root=alwaysOn) sampler and no explicit upstream Sampled, the undecided decision is
        // resolved from the configured sampler (sampled here) rather than forced unsampled. The remote parent is
        // therefore built sampled, so the execution trace is exported instead of dropped.
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        var xrayPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> new ExtractedContext(xrayTraceId, parentSpanId))
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());
        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = exporter.getFinishedSpanItems();
        assertFalse(
                spans.isEmpty(),
                "Undecided upstream defers to the configured sampler (alwaysOn), so spans are exported");
        var workflowSpan = spanByName(spans, "Workflow");
        assertEquals(xrayTraceId, workflowSpan.getTraceId());
        assertEquals(parentSpanId, workflowSpan.getParentSpanId(), "Workflow parents onto the remote span");
        assertTrue(workflowSpan.getSpanContext().isSampled(), "Resolved from the sampler, the trace is sampled");
    }

    @Test
    void xrayExtraction_undecidedSampling_parentBasedNeverSampler_dropsExecutionTrace() {
        // Symmetric case: when the configured sampler's root decision is "drop" (ParentBased(root=alwaysOff)) and the
        // upstream is undecided, the resolved decision is not-sampled, so nothing is exported. This confirms the
        // undecided path follows the sampler in both directions rather than being hardcoded.
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        var xrayPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(Sampler.parentBased(Sampler.alwaysOff()))
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> new ExtractedContext(xrayTraceId, parentSpanId))
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());
        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        assertTrue(
                exporter.getFinishedSpanItems().isEmpty(),
                "An undecided upstream with a drop-sampler resolves to not-sampled");
    }

    @Test
    void xrayExtraction_explicitSampled_remoteParentIsExecutionAncestor() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        var parentSpanId = "53995c3f42cd8ad8";
        var exporter = InMemorySpanExporter.create();
        // Explicit Sampled=1 with a complete parent → the remote context is the execution ancestor directly.
        var xrayPlugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() ->
                                new ExtractedContext(xrayTraceId, parentSpanId, ExtractedContext.Sampling.SAMPLED))
                        .enableMdc(false)
                        .workflowSpanName("Workflow")
                        .build());

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", ARN, true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", ARN, true, InvocationStatus.SUCCEEDED, null));

        var spans = exporter.getFinishedSpanItems();
        var workflowSpan = spanByName(spans, "Workflow");
        var invocationSpan = spanByName(spans, "Invocation");
        assertEquals(xrayTraceId, workflowSpan.getTraceId());
        assertEquals(xrayTraceId, invocationSpan.getTraceId());
        assertEquals(parentSpanId, workflowSpan.getParentSpanId(), "Workflow parents onto the remote span directly");
        assertEquals(
                parentSpanId, invocationSpan.getParentSpanId(), "Invocation parents onto the remote span directly");
        assertTrue(workflowSpan.getLinks().isEmpty(), "No remote-parent link when the remote context is the ancestor");
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
