// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.RetryStrategy;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * End-to-end behavior tests exercising the plugin via the local durable test runner, which drives the real SDK
 * coordinator so the plugin consumes the same hook snapshots (operations, execution input/output, per-op result) it
 * receives in production. These map to the Durable Execution Telemetry conformance behaviors (telemetry-1 …
 * telemetry-18).
 */
class DurableExecutionTelemetryPluginTest {

    private static final class CapturingExporter implements TelemetryExporter {
        final List<DurableExecutionTelemetryRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(DurableExecutionTelemetryRecord record) {
            records.add(record);
        }
    }

    private static final RetryStrategy RETRY_ONCE = RetryStrategies.exponentialBackoff(
            2, Duration.ofSeconds(1), Duration.ofSeconds(1), 2.0, JitterStrategy.NONE);

    private DurableConfig configWith(CapturingExporter exporter, DurableExecutionTelemetryConfig.Builder cfg) {
        var plugin = DurableExecutionTelemetry.durableExecutionTelemetry(
                cfg.addExporter(exporter).build());
        return DurableConfig.builder().withPlugins(plugin).build();
    }

    private OperationRecord op(DurableExecutionTelemetryRecord rec, String name) {
        return rec.operations().stream()
                .filter(o -> name.equals(o.name()))
                .findFirst()
                .orElseThrow();
    }

    // telemetry-1
    @Test
    void basicSuccessEmitsOneRecordWithNamedStepAndEchoedIo() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step("greet", String.class, sc -> "Hello, " + input + "!"),
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        var result = runner.runUntilComplete("World");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, exporter.records.size());
        var rec = exporter.records.get(0);
        assertEquals("DurableExecutionTelemetry", rec.recordType);
        assertEquals("1.0", rec.schemaVersion);
        assertEquals("SUCCEEDED", rec.status());
        assertEquals("World", rec.input);
        assertEquals("Hello, World!", rec.output);
        assertNull(rec.error);
        assertNull(rec.truncated);
        var greet = op(rec, "greet");
        assertEquals("STEP", greet.type());
        assertEquals("SUCCEEDED", greet.status());
        assertEquals(Integer.valueOf(1), greet.attempt());
        assertNull(greet.result(), "result omitted without an override");
    }

    // telemetry-2
    @Test
    void executionFailureRecordCarriesErrorAndFailedOp() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "failing-step",
                        String.class,
                        sc -> {
                            throw new RuntimeException("boom");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        var result = runner.run("World");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, exporter.records.size());
        var rec = exporter.records.get(0);
        assertEquals("FAILED", rec.status());
        assertNotNull(rec.error);
        assertNotNull(rec.error.name());
        assertEquals("FAILED", op(rec, "failing-step").status());
    }

    // telemetry-3
    @Test
    void onFailureModeEmitsNothingForSuccess() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step("greet", String.class, sc -> "hi"),
                configWith(
                        exporter,
                        DurableExecutionTelemetryConfig.builder()
                                .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_FAILURE)));
        runner.runUntilComplete("World");
        assertTrue(exporter.records.isEmpty());
    }

    // telemetry-4
    @Test
    void onFailureModeEmitsExactlyOneFailedRecord() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "failing-step",
                        String.class,
                        sc -> {
                            throw new RuntimeException("boom");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                configWith(
                        exporter,
                        DurableExecutionTelemetryConfig.builder()
                                .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_FAILURE)));
        var result = runner.run("World");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, exporter.records.size());
        assertEquals("FAILED", exporter.records.get(0).status());
    }

    // telemetry-5
    @Test
    void waitYieldsExactlyOneTerminalRecordNoRunningRecord() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.wait("pause", Duration.ofSeconds(1));
                    return context.step("after-wait", String.class, sc -> "done");
                },
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        var result = runner.runUntilComplete("World");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, exporter.records.size(), "on-complete emits exactly one terminal record across suspend/resume");
        assertEquals("SUCCEEDED", exporter.records.get(0).status());
    }

    // telemetry-6
    @Test
    void stepRetryReflectsAttemptNumber() {
        var exporter = new CapturingExporter();
        var calls = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "retried-step",
                        String.class,
                        sc -> {
                            if (calls.incrementAndGet() < 2) {
                                throw new RuntimeException("transient");
                            }
                            return "ok";
                        },
                        StepConfig.builder().retryStrategy(RETRY_ONCE).build()),
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        var result = runner.runUntilComplete("World");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        var retried = op(exporter.records.get(0), "retried-step");
        assertEquals("SUCCEEDED", retried.status());
        assertEquals(Integer.valueOf(2), retried.attempt(), "attempt reflects the retry");
    }

    // telemetry-7
    @Test
    void repeatedNameAggregatesToCountThreeInByName() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    for (int i = 0; i < 3; i++) {
                        context.step("task", Integer.class, sc -> 1);
                    }
                    return "done";
                },
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        runner.runUntilComplete("World");
        assertEquals(1, exporter.records.size());
        Map<String, OperationSummary> byName =
                OperationsIndex.buildOperationsByName(exporter.records.get(0).operations());
        assertEquals(3, byName.get("task").count);
        assertNull(byName.get("task").result, "repeated name drops representative result");
    }

    // telemetry-8
    @Test
    void samplingRateZeroEmitsNothing() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step("greet", String.class, sc -> "hi"),
                configWith(exporter, DurableExecutionTelemetryConfig.builder().samplingRate(0)));
        runner.runUntilComplete("World");
        assertTrue(exporter.records.isEmpty());
    }

    // telemetry-9
    @Test
    void contentOmittedDropsInputAndOutputWithoutTruncationMarkers() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step("greet", String.class, sc -> "Hello, " + input + "!"),
                configWith(
                        exporter,
                        DurableExecutionTelemetryConfig.builder()
                                .content(ContentConfig.builder()
                                        .input(false)
                                        .output(false)
                                        .build())));
        runner.runUntilComplete("World");
        var rec = exporter.records.get(0);
        assertNull(rec.input);
        assertNull(rec.output);
        assertNull(rec.droppedInput, "config-omit is distinct from a size-drop");
        assertNull(rec.droppedOutput);
    }

    // telemetry-10
    @Test
    void includeErrorsFalseDropsOperationError() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "failing-step",
                        String.class,
                        sc -> {
                            throw new RuntimeException("boom");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                configWith(
                        exporter,
                        DurableExecutionTelemetryConfig.builder()
                                .content(ContentConfig.builder()
                                        .includeErrors(false)
                                        .build())));
        var result = runner.run("World");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var rec = exporter.records.get(0);
        assertEquals("FAILED", rec.status());
        assertNull(op(rec, "failing-step").error(), "operation error suppressed by includeErrors:false");
    }

    // telemetry-11
    @Test
    void operationResultOptInSurfacesCheckpointedValue() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step("compute", Integer.class, sc -> 42),
                configWith(
                        exporter,
                        DurableExecutionTelemetryConfig.builder()
                                .content(ContentConfig.builder()
                                        .addOverride(OperationOverride.withResult("compute", r -> r))
                                        .build())));
        runner.runUntilComplete("World");
        var compute = op(exporter.records.get(0), "compute");
        assertEquals(42, compute.result(), "identity override surfaces the checkpointed JSON value");
    }

    // telemetry-13
    @Test
    void topLevelOnlyDropsNestedChildren() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    ParallelDurableFuture parallel = context.parallel(
                            "parallel-work", ParallelConfig.builder().build());
                    parallel.branch(
                            "branch-a", String.class, ctx -> ctx.step("branch-a-step", String.class, sc -> "a"));
                    parallel.branch(
                            "branch-b", String.class, ctx -> ctx.step("branch-b-step", String.class, sc -> "b"));
                    parallel.get();
                    return "done";
                },
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        runner.runUntilComplete("World");
        var rec = exporter.records.get(0);
        assertNotNull(op(rec, "parallel-work"));
        assertTrue(
                rec.operations().stream().allMatch(o -> o.parentId() == null),
                "top-level mode keeps only parentId-less operations");
        assertTrue(rec.operations().stream().noneMatch(o -> "branch-a-step".equals(o.name())));
    }

    // telemetry-14
    @Test
    void fullTreeIncludesChildrenLinkedToParent() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.runInChildContext(
                        "parent-context", String.class, child -> child.step("child-step", String.class, sc -> "c")),
                configWith(
                        exporter,
                        DurableExecutionTelemetryConfig.builder()
                                .operationDetail(DurableExecutionTelemetryConfig.OperationDetail.FULL_TREE)));
        runner.runUntilComplete("World");
        var rec = exporter.records.get(0);
        var parent = op(rec, "parent-context");
        var child = op(rec, "child-step");
        assertEquals("CONTEXT", parent.type());
        assertEquals(parent.id(), child.parentId(), "child parentId links to parent id in full-tree");
    }

    // telemetry-17
    @Test
    void unnamedOperationsAreDropped() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.wait(null, Duration.ofSeconds(1)); // unnamed WAIT -> dropped
                    return context.step("named-step", String.class, sc -> "ok");
                },
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        runner.runUntilComplete("World");
        var rec = exporter.records.get(0);
        assertTrue(rec.operations().stream().allMatch(o -> o.name() != null), "no unnamed operation appears");
        assertNotNull(op(rec, "named-step"));
    }

    // telemetry-18
    @Test
    void summaryMaxAttemptReflectsRetry() {
        var exporter = new CapturingExporter();
        var calls = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "retried-step",
                        String.class,
                        sc -> {
                            if (calls.incrementAndGet() < 2) {
                                throw new RuntimeException("transient");
                            }
                            return "ok";
                        },
                        StepConfig.builder().retryStrategy(RETRY_ONCE).build()),
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));
        runner.runUntilComplete("World");
        Map<String, OperationSummary> byName =
                OperationsIndex.buildOperationsByName(exporter.records.get(0).operations());
        assertEquals(Integer.valueOf(2), byName.get("retried-step").maxAttempt);
        assertEquals(1, byName.get("retried-step").count);
        assertEquals(0, byName.get("retried-step").failedCount);
    }
}
