// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_ARN;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_STATUS;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_EXECUTION_SYNTHETIC_ROOT;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ExecutionRootTest {
    private static final Instant START = Instant.parse("2026-10-02T00:00:00Z");
    private static final String TRACE = "6955b900123456789012345678901234";
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id";

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void anchorIsExportedOnFirstPendingInvocationThenConnectsTerminalWorkflow(boolean executionView, boolean success) {
        var exporter = InMemorySpanExporter.create();
        var plugin = plugin(executionView, exporter, null, Sampler.alwaysOn());
        var effects = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("once", Integer.class, stepCtx -> effects.incrementAndGet());
                    ctx.wait("pause", Duration.ofSeconds(10));
                    if (!success) throw new IllegalArgumentException("expected");
                    return input;
                },
                DurableConfig.builder().withPlugins(plugin).build());
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var first = exporter.getFinishedSpanItems();
        var anchor = named(first, "DurableExecutionRoot");
        assertEquals(true, anchor.getAttributes().get(DURABLE_EXECUTION_SYNTHETIC_ROOT));
        assertFalse(anchor.getParentSpanContext().isValid());
        assertEquals(anchor.getStartEpochNanos(), anchor.getEndEpochNanos());
        assertEquals(StatusCode.UNSET, anchor.getStatus().getStatusCode());
        assertNull(anchor.getAttributes().get(DURABLE_EXECUTION_STATUS));
        assertTrue(first.stream().noneMatch(span -> span.getName().equals("Workflow")));
        assertEquals(anchor.getSpanId(), named(first, "Invocation").getParentSpanId());
        runner.advanceTime();
        assertEquals(
                success ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                runner.runUntilComplete("input").getStatus());
        assertEquals(1, effects.get());
        var all = exporter.getFinishedSpanItems();
        assertEquals(anchor.getSpanId(), named(all, "Workflow").getParentSpanId());
        var anchors = all.stream()
                .filter(span -> span.getName().equals("DurableExecutionRoot"))
                .toList();
        assertEquals(2, anchors.size());
        assertStable(anchor, anchors.get(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fallbackRecoveryKeepsIdentityAndRemoteParentsRemainExternal(boolean executionView) {
        var cases = new ExtractedContext[] {
            null,
            new ExtractedContext("invalid", "bad", ExtractedContext.Sampling.UNDECIDED),
            new ExtractedContext(TRACE, null, ExtractedContext.Sampling.SAMPLED),
            new ExtractedContext(TRACE, "0000000000000000", ExtractedContext.Sampling.SAMPLED)
        };
        for (var extracted : cases) {
            var exporter = InMemorySpanExporter.create();
            var plugin = plugin(executionView, exporter, extracted, Sampler.alwaysOn());
            invoke(plugin, ARN, true, InvocationStatus.RETRYING);
            var root = named(exporter.getFinishedSpanItems(), "DurableExecutionRoot");
            invoke(plugin, ARN, true, InvocationStatus.PENDING); // redelivery after a failed invocation
            invoke(plugin, ARN, false, InvocationStatus.SUCCEEDED);
            var roots = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("DurableExecutionRoot"))
                    .toList();
            assertEquals(3, roots.size());
            roots.forEach(span -> assertStable(root, span));
            assertEquals(new DeterministicIdGenerator().generateExecutionRootSpanId(ARN), root.getSpanId());
            assertEquals(START.getEpochSecond() * 1_000_000_000L, root.getStartEpochNanos());
            if (extracted != null && extracted.hasValidTraceId()) assertEquals(TRACE, root.getTraceId());
        }
        var exporter = InMemorySpanExporter.create();
        var plugin = plugin(
                executionView,
                exporter,
                new ExtractedContext(TRACE, "1234567890123456", ExtractedContext.Sampling.SAMPLED),
                Sampler.alwaysOn());
        invoke(plugin, ARN, true, InvocationStatus.SUCCEEDED);
        assertEquals(2, exporter.getFinishedSpanItems().size());
        assertTrue(exporter.getFinishedSpanItems().stream()
                .allMatch(span -> span.getParentSpanId().equals("1234567890123456")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anchorsHonorSamplingAndAreOwnedByExecutionOnSharedTrace(boolean executionView) {
        for (var extracted :
                new ExtractedContext[] {null, new ExtractedContext(TRACE, null, ExtractedContext.Sampling.NOT_SAMPLED)
                }) {
            var exporter = InMemorySpanExporter.create();
            var plugin = plugin(
                    executionView, exporter, extracted, extracted == null ? Sampler.alwaysOff() : Sampler.alwaysOn());
            invoke(plugin, ARN, true, InvocationStatus.PENDING);
            invoke(plugin, ARN, false, InvocationStatus.SUCCEEDED);
            assertTrue(exporter.getFinishedSpanItems().isEmpty());
        }
        var exporter = InMemorySpanExporter.create();
        var plugin = plugin(
                executionView,
                exporter,
                new ExtractedContext(TRACE, null, ExtractedContext.Sampling.SAMPLED),
                Sampler.alwaysOff());
        invoke(plugin, ARN, true, InvocationStatus.SUCCEEDED);
        invoke(plugin, ARN + "-other", true, InvocationStatus.SUCCEEDED);
        var roots = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("DurableExecutionRoot"))
                .toList();
        assertEquals(2, roots.size());
        assertTrue(roots.stream().allMatch(span -> span.getTraceId().equals(TRACE)));
        assertNotEquals(roots.get(0).getSpanId(), roots.get(1).getSpanId());
        assertNotEquals(
                roots.get(0).getAttributes().get(DURABLE_EXECUTION_ARN),
                roots.get(1).getAttributes().get(DURABLE_EXECUTION_ARN));
    }

    private static void assertStable(SpanData first, SpanData replay) {
        assertEquals(first.getSpanContext(), replay.getSpanContext());
        assertEquals(first.getStartEpochNanos(), replay.getStartEpochNanos());
        assertEquals(first.getEndEpochNanos(), replay.getEndEpochNanos());
        assertEquals(first.getAttributes(), replay.getAttributes());
        assertEquals(first.getResource(), replay.getResource());
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void invoke(DurableExecutionPlugin plugin, String arn, boolean first, InvocationStatus status) {
        plugin.onInvocationStart(new InvocationInfo("request", arn, first, START));
        plugin.onInvocationEnd(new InvocationEndInfo("request", arn, first, status, null));
    }

    private static DurableExecutionPlugin plugin(
            boolean executionView, InMemorySpanExporter exporter, ExtractedContext extracted, Sampler sampler) {
        var builder =
                SdkTracerProvider.builder().setSampler(sampler).addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .contextExtractor(() -> extracted)
                .enableMdc(false)
                .build();
        return executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
    }
}
