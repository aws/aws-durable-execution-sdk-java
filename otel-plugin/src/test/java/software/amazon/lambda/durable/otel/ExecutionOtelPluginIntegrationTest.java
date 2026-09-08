// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.javaagent.testing.FakeJavaAgentTracerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Integration tests for the workflow-rooted {@link ExecutionOtelPlugin} running through the real SDK execution engine
 * (LocalDurableTestRunner). Complements {@link InvocationOtelPluginIntegrationTest}, which covers the invocation-rooted
 * plugin.
 *
 * <p>Execution-view topology: operations are children of the Workflow span and link to the current Invocation span; the
 * whole execution shares one trace.
 */
class ExecutionOtelPluginIntegrationTest {

    private InMemorySpanExporter spanExporter;
    private DurableConfig otelConfig;

    @BeforeEach
    void setUp() {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        spanExporter = InMemorySpanExporter.create();

        var plugin = new ExecutionOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .build());

        otelConfig = DurableConfig.builder().withPlugins(plugin).build();
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    @Test
    void simpleStep_operationsAreChildrenOfWorkflow_andLinkToInvocation() {
        var runner = LocalDurableTestRunner.create(
                String.class, (input, ctx) -> ctx.step("greet", String.class, stepCtx -> "Hello " + input), otelConfig);

        var result = runner.runUntilComplete("World");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 4, "Expected Workflow + Invocation + operation + attempt, got " + spans.size());

        var workflow = spanByName(spans, "Workflow");
        var invocation = spanByName(spans, "Invocation");
        var operation = spanByName(spans, "greet");
        var attempt = spanByName(spans, "greet attempt 1");

        // The whole execution shares one trace.
        assertEquals(workflow.getTraceId(), invocation.getTraceId());
        assertTrue(
                spans.stream().allMatch(s -> s.getTraceId().equals(workflow.getTraceId())),
                "Every span shares the execution trace");

        // Execution-view parenting: the operation is a child of the Workflow span, and its attempt a child of it.
        assertEquals(workflow.getSpanId(), operation.getParentSpanId(), "Operation is a child of the Workflow span");
        assertEquals(operation.getSpanId(), attempt.getParentSpanId(), "Attempt is a child of its operation span");

        // Execution-view correlation: the operation links to the current Invocation span.
        assertTrue(
                operation.getLinks().stream()
                        .anyMatch(l -> l.getSpanContext().getSpanId().equals(invocation.getSpanId())),
                "Operation span links to the current Invocation span");
    }

    @Test
    void waitAcrossInvocations_sharesOneTrace_andExportsWorkflowOnceOnTerminal() {
        // No propagated context, so the execution trace is anchored on a synthetic root derived from the ARN. The
        // runner keeps the execution ARN and start time fixed across reinvocations, matching the backend, so the
        // derived trace ID is the same for every invocation.
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("before-wait", String.class, stepCtx -> "pre");
                    ctx.wait("pause", Duration.ofMinutes(1));
                    ctx.step("after-wait", String.class, stepCtx -> "post");
                    return "done";
                },
                otelConfig);

        // First invocation: step + wait, then suspend (PENDING). The Workflow span must not be exported yet.
        var first = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, first.getStatus());
        assertTrue(
                spanExporter.getFinishedSpanItems().stream()
                        .noneMatch(s -> s.getName().equals("Workflow")),
                "Workflow span must not be exported on a non-terminal invocation");
        var firstInvocationTraceId =
                spanByName(spanExporter.getFinishedSpanItems(), "Invocation").getTraceId();

        // Resume and complete.
        runner.advanceTime();
        var second = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, second.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        // Exactly one Workflow span across the whole execution, exported on the terminal invocation.
        var workflowSpans =
                spans.stream().filter(s -> s.getName().equals("Workflow")).toList();
        assertEquals(1, workflowSpans.size(), "Workflow span is exported exactly once, on the terminal invocation");

        // Single trace per execution: every span from both invocations shares one trace ID, stable across
        // reinvocations.
        var executionTraceId = workflowSpans.get(0).getTraceId();
        assertEquals(firstInvocationTraceId, executionTraceId, "The first invocation already used the execution trace");
        assertTrue(
                spans.stream().allMatch(s -> s.getTraceId().equals(executionTraceId)),
                "Both invocations and the Workflow span share one execution trace");

        // Two Invocation spans (one per run), both on the execution trace.
        assertEquals(
                2, spans.stream().filter(s -> s.getName().equals("Invocation")).count(), "One Invocation span per run");
    }

    @Test
    void wrappedAgentProvider_withRootDroppingSampler_keepsExecutionTreeConsistentlySampled() {
        // The agent provider is hidden behind a classloader wrapper, so the plugin cannot reach the sampler and the
        // bridge published nothing. With no reachable decision, the synthetic execution root is treated as sampled so
        // the execution root and everything parented onto it are sampled together — no orphan operation spans exported
        // under a dropped root.
        OtelPluginAutoConfigurationState.markInstalled();
        GlobalOpenTelemetry.resetForTest();
        var globalExporter = InMemorySpanExporter.create();
        var sdkTracerProvider = SdkTracerProvider.builder()
                .setIdGenerator(new DeterministicIdGenerator())
                .setSampler(Sampler.parentBased(Sampler.alwaysOff()))
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

        var defaultConfig =
                DurableConfig.builder().withPlugins(new ExecutionOtelPlugin()).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step("wrapped-step", String.class, stepCtx -> "Hello " + input),
                defaultConfig);

        var result = runner.runUntilComplete("World");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = globalExporter.getFinishedSpanItems();
        assertSpanExists(spans, "Workflow");
        assertSpanExists(spans, "wrapped-step");
        var workflowTraceId = spans.stream()
                .filter(s -> s.getName().equals("Workflow"))
                .findFirst()
                .orElseThrow()
                .getTraceId();
        // Every exported span is on the one execution trace: the root and its children are sampled consistently.
        assertTrue(
                spans.stream().allMatch(s -> s.getTraceId().equals(workflowTraceId)),
                "All spans share the sampled execution trace");
    }

    // Helpers

    private static SpanData spanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span named '" + name + "' in "
                        + spans.stream().map(SpanData::getName).toList()));
    }

    private static void assertSpanExists(List<SpanData> spans, String expectedName) {
        assertTrue(
                spans.stream().anyMatch(s -> s.getName().equals(expectedName)),
                "Expected span '" + expectedName + "' not found. Got: "
                        + spans.stream().map(SpanData::getName).toList());
    }
}
