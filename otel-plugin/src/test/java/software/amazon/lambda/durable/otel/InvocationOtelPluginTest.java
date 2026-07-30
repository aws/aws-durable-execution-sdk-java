// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.javaagent.testing.FakeJavaAgentTracerProvider;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.plugin.*;

class InvocationOtelPluginTest {

    private InMemorySpanExporter spanExporter;
    private InvocationOtelPlugin plugin;

    @BeforeEach
    void setUp() {
        DeterministicIdGenerator.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        spanExporter = InMemorySpanExporter.create();

        plugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false);
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    @Test
    void defaultConstructor_throwsWhenAutoConfigurationCustomizerProviderIsNotInstalled() {
        GlobalOpenTelemetry.resetForTest();

        var error = assertThrows(IllegalStateException.class, InvocationOtelPlugin::new);

        assertTrue(error.getMessage().contains("OtelPluginAutoConfigurationCustomizerProvider"));
        assertTrue(error.getMessage().contains("OTEL_JAVAAGENT_EXTENSIONS"));
    }

    @Test
    void defaultConstructor_throwsWhenGlobalOpenTelemetryIsNotInitializedBySpi() {
        OtelPluginAutoConfigurationState.markInstalled();
        GlobalOpenTelemetry.resetForTest();

        var error = assertThrows(IllegalStateException.class, InvocationOtelPlugin::new);

        assertTrue(error.getMessage().contains("GlobalOpenTelemetry"));
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

        var defaultPlugin = new InvocationOtelPlugin();
        defaultPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        defaultPlugin.onOperationStart(
                new OperationInfo("op-1", "step", "STEP", "Step", null, Instant.now(), null, false));
        defaultPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        defaultPlugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = globalExporter.getFinishedSpanItems();
        // Plugin creates Workflow + Invocation + operation spans
        assertEquals(3, spans.size());
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("step")));
    }

    @Test
    void defaultConstructor_usesJavaAgentGlobalTracerProviderDirectly_withSeparateAutoConfiguredIdGenerator() {
        OtelPluginAutoConfigurationState.markInstalled();
        GlobalOpenTelemetry.resetForTest();
        var globalExporter = InMemorySpanExporter.create();
        var javaAgentIdGenerator = new DeterministicIdGenerator();
        var sdkTracerProvider = SdkTracerProvider.builder()
                .setIdGenerator(javaAgentIdGenerator)
                .addSpanProcessor(SimpleSpanProcessor.create(globalExporter))
                .build();
        var javaAgentTracerProvider = new FakeJavaAgentTracerProvider(sdkTracerProvider);
        GlobalOpenTelemetry.set(new OpenTelemetry() {
            @Override
            public io.opentelemetry.api.trace.TracerProvider getTracerProvider() {
                return javaAgentTracerProvider;
            }

            @Override
            public ContextPropagators getPropagators() {
                return ContextPropagators.noop();
            }
        });

        var defaultPlugin = new InvocationOtelPlugin();
        defaultPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        defaultPlugin.onOperationStart(
                new OperationInfo("op-1", "step", "STEP", "Step", null, Instant.now(), null, false));
        defaultPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        defaultPlugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = globalExporter.getFinishedSpanItems();
        // Plugin creates Workflow + Invocation + operation spans
        assertEquals(3, spans.size());
        assertTrue(spans.stream().anyMatch(span -> span.getName().equals("step")));
        var expectedIds = new DeterministicIdGenerator();
        expectedIds.setDurableExecutionArn("arn:exec1");
        var stepSpan = spans.stream()
                .filter(span -> span.getName().equals("step"))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedIds.generateSpanIdForOperation("op-1"), stepSpan.getSpanId());
    }

    @Test
    void autoConfigurationCustomizerProvider_installsSharedDeterministicIdGenerator() {
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        var exporter = InMemorySpanExporter.create();
        var autoConfiguration = mock(AutoConfigurationCustomizer.class);
        when(autoConfiguration.addTracerProviderCustomizer(any())).thenReturn(autoConfiguration);

        new OtelPluginAutoConfigurationCustomizerProvider().customize(autoConfiguration);
        assertTrue(OtelPluginAutoConfigurationState.isInstalled());

        @SuppressWarnings("unchecked")
        var customizer = ArgumentCaptor.forClass(BiFunction.class);
        verify(autoConfiguration).addTracerProviderCustomizer(customizer.capture());

        var pluginGenerator = new DeterministicIdGenerator();
        pluginGenerator.setDurableExecutionArn("arn:spi");
        pluginGenerator.setNextSpanOperationId("op-spi");

        @SuppressWarnings("unchecked")
        var tracerProviderCustomizer =
                (BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>)
                        customizer.getValue();
        var tracerProvider = tracerProviderCustomizer
                .apply(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)), null)
                .build();

        var span = tracerProvider.get("test").spanBuilder("step").startSpan();
        span.end();
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        var spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(
                pluginGenerator.generateSpanIdForOperation("op-spi"),
                spans.get(0).getSpanId());
    }

    @Test
    void autoConfigurationCustomizerProvider_isRegisteredAsServiceProvider() {
        assertTrue(ServiceLoader.load(AutoConfigurationCustomizerProvider.class).stream()
                .anyMatch(provider -> provider.type().equals(OtelPluginAutoConfigurationCustomizerProvider.class)));
    }

    @Test
    void invocationStart_usesCurrentSpanContext_whenExtractorReturnsNull() {
        var traceId = "5759e988bd862e3fe1be46a994272793";
        var parentSpanId = "53995c3f42cd8ad8";
        var parentSpanContext =
                SpanContext.create(traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault());

        try (var ignored = Span.wrap(parentSpanContext).makeCurrent()) {
            plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        }
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var invocationSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("Invocation"))
                .findFirst()
                .orElseThrow();
        assertEquals(traceId, invocationSpan.getTraceId());
        assertEquals(parentSpanId, invocationSpan.getParentSpanId());
    }

    @Test
    void invocationStart_and_end_createsSpan() {
        plugin.onInvocationStart(new InvocationInfo(
                "req-123", "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1", true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req-123",
                "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1",
                true,
                InvocationStatus.SUCCEEDED,
                null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size()); // invocation + Workflow

        var span = spans.get(0);
        assertEquals("Invocation", span.getName());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    }

    @Test
    void invocationSpan_hasInternalKind() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals(SpanKind.INTERNAL, span.getKind(), "Invocation span must be INTERNAL kind");
    }

    @Test
    void operationSpanName_usesOperationName_withoutPrefix() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "create-greeting", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "create-greeting",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("create-greeting"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "create-greeting",
                operationSpan.getName(),
                "Operation span should use the operation name directly without 'durable.' prefix");
    }

    @Test
    void attemptSpanName_usesOperationNameWithAttemptNumber() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "process-order", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "process-order", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "process-order attempt 1",
                attemptSpan.getName(),
                "Attempt span should be 'name attempt N' without brackets or prefix");
    }

    @Test
    void operationEnd_withAttempt_stampsAttemptNumberOnOperationSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        plugin.onOperationStart(new OperationInfo("op-1", "flaky", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "flaky", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", 3, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var operationSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("flaky"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                3L,
                operationSpan.getAttributes().get(AttributeKey.longKey("durable.attempt.number")),
                "Operation span should carry total attempt count at end");
    }

    @Test
    void attemptSpan_carriesOperationSubtype() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "process-order", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "process-order", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

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
    void operationEnd_withoutMatchingStart_stampsAttemptNumberOnContinuationSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        // No onOperationStart in this invocation → onOperationEnd takes the continuation-span branch.
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "flaky", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", 2, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var continuationSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("flaky"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                2L,
                continuationSpan.getAttributes().get(AttributeKey.longKey("durable.attempt.number")),
                "Continuation span should carry the attempt count");
        assertEquals(
                "Step",
                continuationSpan.getAttributes().get(AttributeKey.stringKey("durable.operation.subtype")),
                "Continuation span should carry durable.operation.subtype");
    }

    @Test
    void invocationEnd_withFailure_setsErrorStatus() {
        plugin.onInvocationStart(new InvocationInfo("req-123", "arn:exec1", true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req-123", "arn:exec1", true, InvocationStatus.FAILED, new RuntimeException("boom")));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size()); // invocation + Workflow
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    }

    @Test
    void invocationEnd_withRetrying_leavesStatusUnset() {
        plugin.onInvocationStart(new InvocationInfo("req-123", "arn:exec1", true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req-123", "arn:exec1", true, InvocationStatus.RETRYING, new RuntimeException("transient")));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(
                StatusCode.UNSET,
                spans.get(0).getStatus().getStatusCode(),
                "RETRYING invocation span is UNSET (interface cannot distinguish STOPPED/TIMED_OUT)");
    }

    @Test
    void operationStart_createsSpan_operationEnd_endsIt() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        var start = Instant.parse("2026-06-01T10:00:00Z");
        var end = Instant.parse("2026-06-01T10:00:05Z");

        // Operation span created at start
        plugin.onOperationStart(new OperationInfo("op-hash-1", "my-step", "STEP", "Step", null, start, null, false));

        // Operation span ended at completion
        plugin.onOperationEnd(new OperationEndInfo(
                "op-hash-1", "my-step", "STEP", "Step", null, start, end, "SUCCEEDED", null, false, null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(3, spans.size()); // operation + invocation + Workflow

        var operationSpan = spans.stream()
                .filter(s -> s.getName().contains("step"))
                .findFirst()
                .orElseThrow();
        assertEquals("my-step", operationSpan.getName());
    }

    @Test
    void userFunctionStart_and_end_createsAttemptSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "compute", "STEP", "Step", null, Instant.now(), false, 1));

        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "compute", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(3, spans.size()); // attempt + invocation + Workflow

        var attemptSpan = spans.stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        assertTrue(attemptSpan.getName().contains("compute"));
        assertTrue(attemptSpan.getName().contains("attempt 1"));
    }

    @Test
    void userFunctionEnd_withFailure_setsErrorOnAttemptSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

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
        plugin.onInvocationStart(new InvocationInfo("req-1", arn, true, Instant.now()));

        // Step 1: operation starts, user function runs, operation completes
        plugin.onOperationStart(new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));

        // Step 2: operation starts, user function runs, operation completes
        plugin.onOperationStart(new OperationInfo("op-2", "step-b", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-2", "step-b", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-2", "step-b", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-2", "step-b", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        // 2 attempt spans + 2 operation spans + 1 invocation span + 1 Workflow span = 6
        assertEquals(6, spans.size());

        // All spans should share the same trace ID
        var traceId = spans.get(0).getTraceId();
        assertTrue(spans.stream().allMatch(s -> s.getTraceId().equals(traceId)));
    }

    @Test
    void deterministicIds_sameExecutionProducesSameTraceId() {
        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";

        plugin.onInvocationStart(new InvocationInfo("req-1", arn, true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.PENDING, null));

        var firstTraceId = spanExporter.getFinishedSpanItems().get(0).getTraceId();
        spanExporter.reset();

        // Second invocation of same execution
        plugin.onInvocationStart(new InvocationInfo("req-2", arn, false, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", arn, false, InvocationStatus.SUCCEEDED, null));

        var secondTraceId = spanExporter.getFinishedSpanItems().get(0).getTraceId();

        assertEquals(firstTraceId, secondTraceId, "Same execution ARN should produce same trace ID");
    }

    @Test
    void operationNotCompleted_spanEndedAtInvocationEnd() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        // Operation starts but never completes (e.g., wait operation, invocation suspends)
        plugin.onOperationStart(new OperationInfo("op-1", "my-wait", "WAIT", "Wait", null, Instant.now(), null, false));

        // Invocation ends without onOperationEnd being called
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.PENDING, null));

        var spans = spanExporter.getFinishedSpanItems();
        // Should have: operation span (ended at invocation end) + invocation span
        assertEquals(2, spans.size());

        var operationSpan = spans.stream()
                .filter(s -> s.getName().contains("wait"))
                .findFirst()
                .orElseThrow();
        assertEquals("my-wait", operationSpan.getName());
    }

    @Test
    void sampling_disabled_producesNoSpans() {
        spanExporter = InMemorySpanExporter.create();
        var sampledPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff())
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false);

        sampledPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        sampledPlugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step", "STEP", "Step", null, Instant.now(), false, 1));
        sampledPlugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        sampledPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
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
        var xrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size()); // invocation + Workflow
        assertEquals(xrayTraceId, spans.get(0).getTraceId(), "Span should use the extracted X-Ray trace ID");
    }

    @Test
    void xrayExtraction_allSpansShareExtractedTraceId() {
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        var extractedContext = new ExtractedContext(xrayTraceId, null);

        spanExporter = InMemorySpanExporter.create();
        var xrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        xrayPlugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, false));
        xrayPlugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), false, 1));
        xrayPlugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        xrayPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
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
        var xrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size()); // invocation + Workflow

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
        var xrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size()); // invocation + Workflow

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
        var xrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        // First invocation
        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        xrayPlugin.onOperationStart(
                new OperationInfo("op-1", "step-1", "STEP", "Step", null, Instant.now(), null, false));
        xrayPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-1", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.PENDING, null));

        // Second invocation (same execution, same X-Ray Root from backend)
        xrayPlugin.onInvocationStart(new InvocationInfo("req-2", "arn:exec1", false, Instant.now()));
        xrayPlugin.onOperationStart(
                new OperationInfo("op-2", "step-2", "STEP", "Step", null, Instant.now(), null, false));
        xrayPlugin.onOperationEnd(new OperationEndInfo(
                "op-2", "step-2", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
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
        var noXrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false);

        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";
        noXrayPlugin.onInvocationStart(new InvocationInfo("req-1", arn, true, Instant.now()));
        noXrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size()); // invocation + Workflow

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
        var xrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> extractedContext,
                false);

        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(expectedOtelTraceId, spans.get(0).getTraceId());
    }

    // ─── Cross-invocation continuation span tests ────────────────────────

    @Test
    void operationEnd_withoutMatchingStart_createsContinuationSpanWithLink() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        // onOperationEnd without a prior onOperationStart — operation completed between invocations
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

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var spans = spanExporter.getFinishedSpanItems();
        assertEquals(3, spans.size()); // continuation + invocation + Workflow

        var continuationSpan = spans.stream()
                .filter(s -> s.getName().contains("wait"))
                .findFirst()
                .orElseThrow();
        assertEquals("my-wait", continuationSpan.getName());
        assertFalse(continuationSpan.getLinks().isEmpty(), "Continuation span should have a Link");
    }

    @Test
    void operationEnd_withoutMatchingStart_usesOperationStartTimestamp() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        var operationStart = Instant.parse("2026-01-15T08:30:00Z");
        var operationEnd = Instant.parse("2026-01-15T09:00:00Z");

        // onOperationEnd without a prior onOperationStart — continuation span
        plugin.onOperationEnd(new OperationEndInfo(
                "op-wait-1",
                "my-wait",
                "WAIT",
                "Wait",
                null,
                operationStart,
                operationEnd,
                "SUCCEEDED",
                null,
                false,
                null));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var continuationSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("wait"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                operationStart.toEpochMilli(),
                continuationSpan.getStartEpochNanos() / 1_000_000,
                "Continuation span should use the operation's startTimestamp");
    }

    @Test
    void operationEnd_withoutMatchingStart_withError_setsErrorStatus() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        plugin.onOperationEnd(new OperationEndInfo(
                "op-cb-1",
                "my-callback",
                "CALLBACK",
                "Callback",
                null,
                Instant.now(),
                Instant.now(),
                "FAILED",
                null,
                false,
                new RuntimeException("timed out")));

        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var continuationSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("callback"))
                .findFirst()
                .orElseThrow();
        assertEquals(StatusCode.ERROR, continuationSpan.getStatus().getStatusCode());
        assertFalse(continuationSpan.getLinks().isEmpty());
    }

    // ─── CONTEXT operation handling ─────────────────────────────────────

    @Test
    void contextOperation_doesNotCreateAttemptSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        // Create operation span first so the CONTEXT user function has a parent
        plugin.onOperationStart(new OperationInfo(
                "op-1", "child-ctx", "CONTEXT", "RunInChildContext", null, Instant.now(), null, false));

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

        // Should only have the operation span + invocation span — no attempt span
        var spans = spanExporter.getFinishedSpanItems();
        var attemptSpans =
                spans.stream().filter(s -> s.getName().contains("attempt")).toList();
        assertTrue(attemptSpans.isEmpty(), "CONTEXT operations should not produce attempt spans");
    }

    // ─── Attempt span cleanup at invocation end ──────────────────────────

    @Test
    void attemptSpan_endedAtInvocationEnd_whenUserFunctionEndNotCalled() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

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
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));

        // Parent context operation
        plugin.onOperationStart(new OperationInfo(
                "op-parent", "my-context", "CONTEXT", "RunInChildContext", null, Instant.now(), null, false));

        // Child operation with parentId pointing to parent
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
        plugin.onInvocationStart(new InvocationInfo("req-1", arn, true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "step-A", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-A", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-A", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-A", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onOperationStart(new OperationInfo("op-2", "pause", "WAIT", "Wait", null, Instant.now(), null, false));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.PENDING, null));

        // Invocation 1 should have: step op + step attempt + wait (PENDING) + invocation = 4
        assertEquals(4, spanExporter.getFinishedSpanItems().size());
        var inv1TraceId = spanExporter.getFinishedSpanItems().get(0).getTraceId();

        spanExporter.reset();

        // Invocation 2: wait completed between invocations, new step runs
        plugin.onInvocationStart(new InvocationInfo("req-2", arn, false, Instant.now()));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-2", "pause", "WAIT", "Wait", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onOperationStart(new OperationInfo("op-3", "step-B", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-3", "step-B", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-3", "step-B", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-3", "step-B", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", arn, false, InvocationStatus.SUCCEEDED, null));

        var inv2Spans = spanExporter.getFinishedSpanItems();
        // wait continuation + step-B op + step-B attempt + invocation + Workflow = 5
        assertEquals(5, inv2Spans.size());

        // Same trace ID across invocations
        var inv2TraceId = inv2Spans.get(0).getTraceId();
        assertEquals(inv1TraceId, inv2TraceId);

        // Wait continuation should have a Link
        var waitContinuation = inv2Spans.stream()
                .filter(s -> s.getName().contains("pause"))
                .findFirst()
                .orElseThrow();
        assertFalse(waitContinuation.getLinks().isEmpty());
    }

    // ─── Cross-invocation step retry scenario ────────────────────────────

    @Test
    void crossInvocation_stepRetry_attemptsParentedToRespectiveInvocations() {
        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";

        // Invocation 1: step starts, attempt 1 fails, invocation suspended during retry poll
        plugin.onInvocationStart(new InvocationInfo("req-1", arn, true, Instant.now()));
        plugin.onOperationStart(
                new OperationInfo("op-1", "process-payment", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "process-payment", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1",
                "process-payment",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                false,
                new RuntimeException("payment failed")));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", arn, true, InvocationStatus.PENDING, null));

        var inv1Spans = spanExporter.getFinishedSpanItems();
        // operation span (PENDING) + attempt 1 span + invocation span = 3
        assertEquals(3, inv1Spans.size());

        var inv1OperationSpan = inv1Spans.stream()
                .filter(s ->
                        s.getName().contains("process-payment") && !s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        var attempt1Span = inv1Spans.stream()
                .filter(s -> s.getName().contains("attempt 1"))
                .findFirst()
                .orElseThrow();

        // Attempt 1 should be parented to invocation 1's operation span
        assertEquals(
                inv1OperationSpan.getSpanId(),
                attempt1Span.getParentSpanId(),
                "Attempt 1 should be parented to invocation 1's operation span");

        spanExporter.reset();

        // Invocation 2: step is replayed (continuation), attempt 2 executes and succeeds
        plugin.onInvocationStart(new InvocationInfo("req-2", arn, false, Instant.now()));
        // isReplay=true: this operation already exists in the execution state
        plugin.onOperationStart(
                new OperationInfo("op-1", "process-payment", "STEP", "Step", null, Instant.now(), null, true));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "process-payment", "STEP", "Step", null, Instant.now(), false, 2));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "process-payment", "STEP", "Step", null, Instant.now(), Instant.now(), false, 2, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1",
                "process-payment",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                "SUCCEEDED",
                null,
                false,
                null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-2", arn, false, InvocationStatus.SUCCEEDED, null));

        var inv2Spans = spanExporter.getFinishedSpanItems();
        // operation span + attempt 2 span + invocation span + Workflow span = 4
        assertEquals(4, inv2Spans.size());

        var inv2OperationSpan = inv2Spans.stream()
                .filter(s ->
                        s.getName().contains("process-payment") && !s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();
        var attempt2Span = inv2Spans.stream()
                .filter(s -> s.getName().contains("attempt 2"))
                .findFirst()
                .orElseThrow();

        // Attempt 2 should be parented to invocation 2's operation span
        assertEquals(
                inv2OperationSpan.getSpanId(),
                attempt2Span.getParentSpanId(),
                "Attempt 2 should be parented to invocation 2's operation span");

        // The two operation spans must have DIFFERENT span IDs (the bug was they were the same)
        assertNotEquals(
                inv1OperationSpan.getSpanId(),
                inv2OperationSpan.getSpanId(),
                "Continuation operation span must have a different span ID from the original");

        // The continuation operation span should have a Link to the original for correlation
        assertFalse(
                inv2OperationSpan.getLinks().isEmpty(),
                "Continuation operation span should have a Link to the original");

        // All spans share the same trace ID
        var allSpans = new java.util.ArrayList<>(inv1Spans);
        allSpans.addAll(inv2Spans);
        var traceId = allSpans.get(0).getTraceId();
        assertTrue(
                allSpans.stream().allMatch(s -> s.getTraceId().equals(traceId)),
                "All spans across invocations should share the same trace ID");
    }

    // ─── Workflow span + links ───────────────────────────────────────────

    @Test
    void workflowSpan_exportedOnTerminal_internal_deterministicId() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec-wf", true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec-wf", true, InvocationStatus.SUCCEEDED, null));

        var workflow = spanByName("Workflow");
        assertEquals(SpanKind.INTERNAL, workflow.getKind(), "Workflow span must be INTERNAL");
        assertEquals(StatusCode.OK, workflow.getStatus().getStatusCode());
        assertTrue(workflow.getSpanId().matches("[0-9a-f]{16}"), "Workflow span ID should be 16 hex chars");
    }

    @Test
    void workflowSpan_notExportedOnNonTerminal() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.PENDING, null));

        assertTrue(
                spanExporter.getFinishedSpanItems().stream()
                        .noneMatch(s -> s.getName().equals("Workflow")),
                "Workflow span must not be exported on a non-terminal invocation");
    }

    @Test
    void operationAndAttemptSpans_linkToWorkflowSpan() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec-wf", true, Instant.now()));
        plugin.onOperationStart(new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, false));
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));
        plugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", 1, false, null));
        plugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec-wf", true, InvocationStatus.SUCCEEDED, null));

        var workflowId = spanByName("Workflow").getSpanId();
        var operationSpan = spanByName("step-a");
        var attemptSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().contains("attempt"))
                .findFirst()
                .orElseThrow();

        assertTrue(hasLinkTo(operationSpan, workflowId), "Operation span should link to the Workflow span");
        assertTrue(hasLinkTo(attemptSpan, workflowId), "Attempt span should link to the Workflow span");
    }

    @Test
    void operationLinksToWorkflow_withXRayContext() {
        // "Other case": invocation span is parented to the X-Ray segment, but operation spans still link to Workflow.
        var exporter = InMemorySpanExporter.create();
        var xrayPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                () -> new ExtractedContext("5759e988bd862e3fe1be46a994272793", "53995c3f42cd8ad8"),
                false);
        xrayPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        xrayPlugin.onOperationStart(
                new OperationInfo("op-1", "step-a", "STEP", "Step", null, Instant.now(), null, false));
        xrayPlugin.onOperationEnd(new OperationEndInfo(
                "op-1", "step-a", "STEP", "Step", null, Instant.now(), Instant.now(), "SUCCEEDED", null, false, null));
        xrayPlugin.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        var workflowId = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("Workflow"))
                .findFirst()
                .orElseThrow()
                .getSpanId();
        var operationSpan = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("step-a"))
                .findFirst()
                .orElseThrow();
        assertTrue(
                operationSpan.getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(workflowId)),
                "Operation span should link to Workflow span even when the invocation is parented to the X-Ray segment");
    }

    @Test
    void workflowSpanName_isConfigurable() {
        var exporter = InMemorySpanExporter.create();
        var customPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                () -> null,
                false,
                "MyWorkflow");
        customPlugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        customPlugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec1", true, InvocationStatus.SUCCEEDED, null));

        assertTrue(
                exporter.getFinishedSpanItems().stream()
                        .anyMatch(s -> s.getName().equals("MyWorkflow")),
                "Workflow span should use the configured name");
        assertTrue(
                exporter.getFinishedSpanItems().stream()
                        .noneMatch(s -> s.getName().equals("Workflow")),
                "Default 'Workflow' name should not appear when a custom name is configured");
    }

    @Test
    void failedInvocation_setsErrorOnBothWorkflowAndInvocationSpans() {
        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec1", true, Instant.now()));
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req-1", "arn:exec1", true, InvocationStatus.FAILED, new RuntimeException("boom")));

        var workflowSpan = spanByName("Workflow");
        var invocationSpan = spanByName("Invocation");

        assertEquals(
                StatusCode.ERROR,
                workflowSpan.getStatus().getStatusCode(),
                "Workflow span should be ERROR on a FAILED invocation");
        assertEquals(
                StatusCode.ERROR,
                invocationSpan.getStatus().getStatusCode(),
                "Invocation span should be ERROR on a FAILED invocation");
        assertTrue(
                workflowSpan.getEvents().stream().anyMatch(e -> e.getName().equals("exception")),
                "Workflow span should record the execution exception on FAILED");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private io.opentelemetry.sdk.trace.data.SpanData spanByName(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span named '" + name + "'"));
    }

    private static boolean hasLinkTo(io.opentelemetry.sdk.trace.data.SpanData span, String spanId) {
        return span.getLinks().stream()
                .anyMatch(l -> l.getSpanContext().getSpanId().equals(spanId));
    }
}
