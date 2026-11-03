// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class OtelPluginIntegrationTest {
    @AfterEach
    void resetTelemetry() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    @ParameterizedTest
    @EnumSource(OtelPlugin.Mode.class)
    void configurationAndHierarchySurviveSuspensionAndReplay(OtelPlugin.Mode mode) {
        var exporter = InMemorySpanExporter.create();
        var extractions = new AtomicInteger();
        var effects = new AtomicInteger();
        var settings = OtelPluginConfig.builder()
                .contextExtractor(() -> {
                    extractions.incrementAndGet();
                    return null;
                })
                .workflowSpanName("Custom Workflow")
                .instrumentationName("custom-scope")
                .enableMdc(false)
                .build();
        var factory = OtelPlugin.factory(
                mode, SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)), settings);
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("before", Integer.class, step -> {
                        assertNull(MDC.get("trace_id"));
                        return effects.incrementAndGet();
                    });
                    context.wait("pause", Duration.ofSeconds(1));
                    return context.step("after", String.class, step -> input);
                },
                DurableConfig.builder().withPlugins(factory).build());
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.advanceTime();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());
        assertEquals(1, effects.get(), "Completed user code must be skipped on replay");
        assertEquals(2, extractions.get());
        var spans = exporter.getFinishedSpanItems();
        assertEquals(
                1,
                spans.stream()
                        .filter(span -> span.getName().equals("Custom Workflow"))
                        .count());
        assertEquals(
                2,
                spans.stream()
                        .filter(span -> span.getName().equals("Invocation"))
                        .count());
        assertTrue(spans.stream()
                .allMatch(span -> span.getInstrumentationScopeInfo().getName().equals("custom-scope")));
        assertHierarchy(spans, mode, "Custom Workflow", "before");
        assertHierarchy(spans, mode, "Custom Workflow", "after");
    }

    @ParameterizedTest
    @EnumSource(OtelPlugin.Mode.class)
    void globalProviderConfigurationIsForwarded(OtelPlugin.Mode mode) {
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        var exporter = InMemorySpanExporter.create();
        var factory = OtelPlugin.factory(
                mode,
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .workflowSpanName("Global Workflow")
                        .enableMdc(false)
                        .build());
        assertFalse(GlobalOpenTelemetry.isSet());
        // Match the ID generator installed by the ADOT auto-configuration customizer.
        try (var provider = SdkTracerProvider.builder()
                .setIdGenerator(new DeterministicIdGenerator())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            OpenTelemetrySdk.builder().setTracerProvider(provider).buildAndRegisterGlobal();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> context.step("work", String.class, step -> input),
                    DurableConfig.builder().withPlugins(factory).build());
            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());
            assertHierarchy(exporter.getFinishedSpanItems(), mode, "Global Workflow", "work");
        }
    }

    @ParameterizedTest
    @EnumSource(OtelPlugin.Mode.class)
    void oneFactoryKeepsConcurrentExecutionsIsolated(OtelPlugin.Mode mode) throws Exception {
        var exporter = InMemorySpanExporter.create();
        var factory = OtelPlugin.factory(
                mode,
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(false)
                        .build());
        var config = DurableConfig.builder().withPlugins(factory).build();
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of("first", "second").stream()
                    .map(input -> executor.submit(() -> {
                        var runner = LocalDurableTestRunner.create(
                                String.class,
                                (value, context) -> context.step("work", String.class, step -> {
                                    assertDoesNotThrow(() -> barrier.await(10, TimeUnit.SECONDS));
                                    return value;
                                }),
                                config);
                        assertEquals(
                                ExecutionStatus.SUCCEEDED,
                                runner.runUntilComplete(input).getStatus());
                    }))
                    .toList();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        var spans = exporter.getFinishedSpanItems();
        var workflows =
                spans.stream().filter(span -> span.getName().equals("Workflow")).toList();
        assertEquals(2, workflows.size());
        assertNotEquals(workflows.get(0).getTraceId(), workflows.get(1).getTraceId());
        for (var workflow : workflows) {
            var executionSpans = spans.stream()
                    .filter(span -> span.getTraceId().equals(workflow.getTraceId()))
                    .toList();
            assertEquals(
                    1,
                    executionSpans.stream()
                            .filter(span -> span.getName().equals("Invocation"))
                            .count());
            assertEquals(
                    1,
                    executionSpans.stream()
                            .filter(span -> span.getName().equals("work"))
                            .count());
            assertTrue(executionSpans.stream()
                    .allMatch(span -> span.getAttributes()
                            .get(AttributeKey.stringKey("durable.execution.arn"))
                            .equals(workflow.getAttributes().get(AttributeKey.stringKey("durable.execution.arn")))));
            assertHierarchy(executionSpans, mode, "Workflow", "work");
        }
    }

    private static void assertHierarchy(
            List<SpanData> spans, OtelPlugin.Mode mode, String workflowName, String operation) {
        var workflow = span(spans, workflowName);
        var work = span(spans, operation);
        assertEquals(workflow.getTraceId(), work.getTraceId());
        if (mode == OtelPlugin.Mode.EXECUTION) {
            assertEquals(workflow.getSpanId(), work.getParentSpanId());
        } else {
            assertTrue(spans.stream()
                    .anyMatch(candidate -> candidate.getName().equals("Invocation")
                            && candidate.getSpanId().equals(work.getParentSpanId())));
        }
    }

    private static SpanData span(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
