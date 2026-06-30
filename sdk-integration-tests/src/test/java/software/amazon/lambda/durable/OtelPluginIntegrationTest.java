// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.otel.OtelPlugin;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Integration tests verifying the OTel plugin produces correct spans when running with the real SDK execution engine
 * (LocalDurableTestRunner).
 */
class OtelPluginIntegrationTest {

    private InMemorySpanExporter spanExporter;
    private DurableConfig otelConfig;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();

        var plugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                false);

        otelConfig = DurableConfig.builder().withPlugins(plugin).build();
    }

    @Test
    void simpleStep_producesInvocationAndOperationAndAttemptSpans() {
        var runner = LocalDurableTestRunner.create(
                String.class, (input, ctx) -> ctx.step("greet", String.class, stepCtx -> "Hello " + input), otelConfig);

        var result = runner.runUntilComplete("World");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        // Should have: invocation + operation (backfilled) + attempt = 3
        assertTrue(spans.size() >= 3, "Expected at least 3 spans, got " + spans.size());

        // Verify span names
        assertSpanExists(spans, "invocation");
        assertSpanExists(spans, "greet");
        assertSpanExists(spans, "greet attempt 1");

        // All spans share the same trace ID
        var traceId = spans.get(0).getTraceId();
        assertTrue(spans.stream().allMatch(s -> s.getTraceId().equals(traceId)));
    }

    @Test
    void multipleSteps_producesSpansForEach() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("step-a", String.class, stepCtx -> "A");
                    ctx.step("step-b", String.class, stepCtx -> "B");
                    ctx.step("step-c", String.class, stepCtx -> "C");
                    return "done";
                },
                otelConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        // 1 invocation + 3 operations + 3 attempts = 7
        assertTrue(spans.size() >= 7, "Expected at least 7 spans, got " + spans.size());

        assertSpanExists(spans, "step-a");
        assertSpanExists(spans, "step-b");
        assertSpanExists(spans, "step-c");
        assertSpanExists(spans, "step-a attempt 1");
        assertSpanExists(spans, "step-b attempt 1");
        assertSpanExists(spans, "step-c attempt 1");
    }

    @Test
    void retryStep_producesMultipleAttemptSpans() {
        var attempts = new AtomicInteger(0);
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "flaky",
                        String.class,
                        stepCtx -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new RuntimeException("not yet");
                            }
                            return "success";
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.DEFAULT)
                                .build()),
                otelConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        // Should have multiple attempt spans for the retry step
        var attemptSpans = spans.stream()
                .filter(s -> s.getName().contains("flaky") && s.getName().contains("attempt"))
                .toList();
        assertTrue(attemptSpans.size() >= 3, "Should have at least 3 attempt spans, got " + attemptSpans.size());
    }

    @Test
    void waitAndResume_producesSpansAcrossInvocations() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("before-wait", String.class, stepCtx -> "pre");
                    ctx.wait("pause", Duration.ofMinutes(1));
                    ctx.step("after-wait", String.class, stepCtx -> "post");
                    return "done";
                },
                otelConfig);

        // First invocation: step + wait → suspend
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        var spansAfterFirstInvocation = spanExporter.getFinishedSpanItems().size();
        assertTrue(spansAfterFirstInvocation > 0, "Should have spans from first invocation");

        // Advance time and resume
        runner.advanceTime();
        var result2 = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());

        var allSpans = spanExporter.getFinishedSpanItems();

        // Should have spans from both invocations
        assertTrue(allSpans.size() > spansAfterFirstInvocation, "Second invocation should produce additional spans");

        // Verify both invocations produced invocation spans
        var invocationSpans =
                allSpans.stream().filter(s -> s.getName().equals("invocation")).toList();
        assertEquals(2, invocationSpans.size(), "Should have 2 invocation spans (one per run)");
    }

    @Test
    void waitCompletedDuringSuspension_producesOperationSpan() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("before-wait", String.class, stepCtx -> "pre");
                    ctx.wait("pause", Duration.ofMinutes(1));
                    ctx.step("after-wait", String.class, stepCtx -> "post");
                    return "done";
                },
                otelConfig);

        // First invocation: step completes, wait starts, suspend
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        // The wait operation span should be open (ended with PENDING status at invocation end)
        var spansAfterFirst = spanExporter.getFinishedSpanItems();
        var waitSpansAfterFirst = spansAfterFirst.stream()
                .filter(s -> s.getName().equals("pause"))
                .toList();
        assertEquals(1, waitSpansAfterFirst.size(), "Wait span should exist after first invocation (ended as PENDING)");

        // Advance time (wait completes externally) and resume
        runner.advanceTime();
        var result2 = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());

        // After second invocation, the wait should have a second operation span
        // (fired by onOperationEnd via updatedOperationIds) showing it completed
        var allSpans = spanExporter.getFinishedSpanItems();
        var waitSpansTotal =
                allSpans.stream().filter(s -> s.getName().equals("pause")).toList();
        assertEquals(
                2,
                waitSpansTotal.size(),
                "Wait should have 2 spans: one PENDING from first invocation, one completed from second. Got: "
                        + allSpans.stream()
                                .map(SpanData::getName)
                                .filter(n -> n.contains("pause"))
                                .toList());
    }

    @Test
    void invokeFailedDuringSuspension_producesErrorSpan() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    try {
                        ctx.invoke("call-target", "target-fn", "{}", String.class);
                    } catch (Exception e) {
                        // expected failure
                    }
                    return "handled";
                },
                otelConfig);

        // First invocation: invoke starts, suspends waiting for target
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        // Target fails while Lambda is frozen
        runner.failChainedInvoke(
                "call-target",
                software.amazon.awssdk.services.lambda.model.ErrorObject.builder()
                        .errorType("TargetError")
                        .errorMessage("target function failed")
                        .build());

        // Resume
        var result2 = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());

        // The invoke operation span from the second invocation should have ERROR status
        var allSpans = spanExporter.getFinishedSpanItems();
        var invokeSpans = allSpans.stream()
                .filter(s -> s.getName().contains("call-target"))
                .toList();
        assertTrue(invokeSpans.size() >= 2, "Should have at least 2 invoke spans (one PENDING, one ended with error)");

        // The span from the second invocation should be marked as error
        var errorSpan = invokeSpans.stream()
                .filter(s -> s.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR)
                .findFirst();
        assertTrue(
                errorSpan.isPresent(),
                "Should have an invoke span with ERROR status when invoke fails during suspension");
    }

    @Test
    void childContext_producesNestedSpans() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.runInChildContext("child", String.class, childCtx -> {
                    return childCtx.step("inner", String.class, stepCtx -> "from-child");
                }),
                otelConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        // Should have: invocation + child context operation + child context attempt (fn)
        //            + inner step operation + inner step attempt = 5
        assertTrue(spans.size() >= 5, "Should have at least 5 spans for child context, got " + spans.size());

        assertSpanExists(spans, "child");
        assertSpanExists(spans, "inner");
    }

    @Test
    void failedStep_producesErrorSpan() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "fail",
                        String.class,
                        stepCtx -> {
                            throw new RuntimeException("boom");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                otelConfig);

        var result = runner.run("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        // Invocation span should have error status
        var invocationSpan = spans.stream()
                .filter(s -> s.getName().equals("invocation"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                io.opentelemetry.api.trace.StatusCode.ERROR,
                invocationSpan.getStatus().getStatusCode(),
                "Invocation span should have ERROR status");
    }

    @Test
    void sampling_off_producesNoSpans() {
        var sampledExporter = InMemorySpanExporter.create();

        var noSamplePlugin = new OtelPlugin(
                SdkTracerProvider.builder()
                        .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff())
                        .addSpanProcessor(SimpleSpanProcessor.create(sampledExporter)),
                () -> null,
                false);

        var noSampleConfig = DurableConfig.builder().withPlugins(noSamplePlugin).build();

        var runner = LocalDurableTestRunner.create(
                String.class, (input, ctx) -> ctx.step("step", String.class, stepCtx -> "result"), noSampleConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        assertTrue(sampledExporter.getFinishedSpanItems().isEmpty(), "0% sampling should produce no spans");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static void assertSpanExists(List<SpanData> spans, String expectedName) {
        assertTrue(
                spans.stream().anyMatch(s -> s.getName().equals(expectedName)),
                "Expected span '" + expectedName + "' not found. Got: "
                        + spans.stream().map(SpanData::getName).toList());
    }

    // ─── Additional scenario tests ──────────────────────────────────────

    @Test
    void callback_producesSpansInFirstInvocation() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    var result = ctx.waitForCallback("approval", String.class, (callbackId, stepCtx) -> {});
                    return result;
                },
                otelConfig);

        // First invocation: callback starts, suspends
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        var spans = spanExporter.getFinishedSpanItems();
        assertTrue(spans.size() >= 2, "Should have invocation + callback spans");

        // Verify callback operation span exists
        assertTrue(
                spans.stream().anyMatch(s -> s.getName().contains("approval")),
                "Should have callback span. Got: "
                        + spans.stream().map(SpanData::getName).toList());
    }

    @Test
    void mapOperation_producesSpansForEachItem() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.map(
                            "batch",
                            List.of("a", "b", "c"),
                            String.class,
                            (item, index, childCtx) ->
                                    childCtx.step("process-" + item, String.class, stepCtx -> item.toUpperCase()));
                    return "done";
                },
                otelConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        // Should have: invocation + map operation + map attempt + item steps + item attempts
        assertTrue(spans.size() >= 9, "Expected at least 9 spans for map, got " + spans.size());

        assertTrue(
                spans.stream().anyMatch(s -> s.getName().contains("batch")),
                "Should have map operation span. Got: "
                        + spans.stream().map(SpanData::getName).toList());
    }

    @Test
    void parallelOperation_producesSpansForEachBranch() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    var parallel = ctx.parallel("fan-out");
                    try (parallel) {
                        parallel.branch(
                                "branch-A",
                                String.class,
                                childCtx -> childCtx.step("step-A", String.class, stepCtx -> "A"));
                        parallel.branch(
                                "branch-B",
                                String.class,
                                childCtx -> childCtx.step("step-B", String.class, stepCtx -> "B"));
                    }
                    parallel.get();
                    return "done";
                },
                otelConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        assertTrue(spans.size() >= 7, "Expected at least 7 spans for parallel, got " + spans.size());

        assertTrue(
                spans.stream().anyMatch(s -> s.getName().contains("fan-out")),
                "Should have parallel operation span. Got: "
                        + spans.stream().map(SpanData::getName).toList());
        assertSpanExists(spans, "step-A");
        assertSpanExists(spans, "step-B");
    }

    @Test
    void nestedChildContext_producesCorrectHierarchy() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.runInChildContext("outer", String.class, outerCtx -> {
                    return outerCtx.runInChildContext("inner", String.class, innerCtx -> {
                        return innerCtx.step("deep-step", String.class, stepCtx -> "deep");
                    });
                }),
                otelConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        assertSpanExists(spans, "outer");
        assertSpanExists(spans, "inner");
        assertSpanExists(spans, "deep-step");
    }

    @Test
    void multipleWaits_producesSpansAcrossMultipleInvocations() {
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.wait("wait-A", Duration.ofMinutes(1));
                    ctx.wait("wait-B", Duration.ofMinutes(1));
                    return "done";
                },
                otelConfig);

        // Invocation 1: wait-A starts, suspends
        var result1 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result1.getStatus());

        runner.advanceTime();

        // Invocation 2: wait-A completed, wait-B starts, suspends
        var result2 = runner.run("input");
        assertEquals(ExecutionStatus.PENDING, result2.getStatus());

        runner.advanceTime();

        // Invocation 3: wait-B completed, execution succeeds
        var result3 = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result3.getStatus());

        var allSpans = spanExporter.getFinishedSpanItems();

        // Should have 3 invocation spans
        var invocationSpans =
                allSpans.stream().filter(s -> s.getName().equals("invocation")).toList();
        assertEquals(3, invocationSpans.size(), "Should have 3 invocation spans");

        // Should have wait-A and wait-B spans
        assertTrue(allSpans.stream().anyMatch(s -> s.getName().equals("wait-A")), "Should have wait-A span");
        assertTrue(allSpans.stream().anyMatch(s -> s.getName().equals("wait-B")), "Should have wait-B span");
    }

    @Test
    void waitForCondition_producesSpansWithAttempts() {
        var pollCount = new AtomicInteger(0);
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.waitForCondition("check-ready", String.class, (state, stepCtx) -> {
                        if (pollCount.incrementAndGet() >= 3) {
                            return WaitForConditionResult.stopPolling("ready");
                        }
                        return WaitForConditionResult.continuePolling("not-yet");
                    });
                    return "done";
                },
                otelConfig);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var spans = spanExporter.getFinishedSpanItems();

        assertTrue(
                spans.stream().anyMatch(s -> s.getName().contains("check-ready")),
                "Should have waitForCondition span. Got: "
                        + spans.stream().map(SpanData::getName).toList());
    }
}
