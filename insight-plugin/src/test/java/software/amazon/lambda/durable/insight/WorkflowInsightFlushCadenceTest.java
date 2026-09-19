// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Pins the flush cadence at the plugin boundary: every invocation end that reaches the exporters flushes them — at most
 * once, and exactly once when ends do not overlap — including the ends that emit no record, while a sampled-out
 * execution flushes not at all. Ends that overlap may share one flush; no end's record is ever left unflushed.
 */
class WorkflowInsightFlushCadenceTest {

    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static String arn(int index) {
        return "arn:aws:lambda:us-west-2:111122223333:function:f:$LATEST/durable-execution/exec-" + index + "/inv-1";
    }

    /** Counts exports and flushes, and remembers how many exports had happened when each flush ran. */
    private static final class CountingExporter implements InsightExporter {
        final List<String> exported = new CopyOnWriteArrayList<>();
        final AtomicInteger flushes = new AtomicInteger();
        final List<Integer> exportsAtFlush = new CopyOnWriteArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            exported.add(record.status() + "@" + record.executionArn());
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
            exportsAtFlush.add(exported.size());
        }
    }

    /** The environment: one factory, one scheduler, one set of exporters, however many invocations follow. */
    private static DurableExecutionPluginFactory environment(
            WorkflowInsightConfig.EmitMode mode, Double samplingRate, CountingExporter... exporters) {
        var builder = WorkflowInsightConfig.builder().emitMode(mode);
        for (CountingExporter exporter : exporters) {
            builder = builder.addExporter(exporter);
        }
        if (samplingRate != null) {
            builder = builder.samplingRate(samplingRate);
        }
        return WorkflowInsight.workflowInsight(builder.build());
    }

    @Test
    void anInvocationEndThatEmitsARecordFlushesEveryExporterExactlyOnce() {
        var first = new CountingExporter();
        var second = new CountingExporter();
        var environment = environment(WorkflowInsightConfig.EmitMode.ON_COMPLETE, null, first, second);

        var plugin = Executions.started(environment, start(arn(0)));
        plugin.onInvocationEnd(end(arn(0), InvocationStatus.SUCCEEDED));

        for (CountingExporter exporter : List.of(first, second)) {
            assertEquals(List.of("SUCCEEDED@" + arn(0)), exporter.exported);
            assertEquals(1, exporter.flushes.get(), "exactly one flush per invocation end");
            assertEquals(List.of(1), exporter.exportsAtFlush, "the flush follows the record it is meant to flush");
        }
    }

    @Test
    void anInvocationEndThatEmitsNothingStillFlushesEveryExporterExactlyOnce() {
        // ON_COMPLETE + a non-terminal suspend, and ON_FAILURE + a success: both are sampled in, both emit no record,
        // and both must still flush — a buffering exporter's earlier records depend on it.
        record Case(String name, WorkflowInsightConfig.EmitMode mode, InvocationStatus status) {}
        List<Case> cases = List.of(
                new Case("ON_COMPLETE + PENDING", WorkflowInsightConfig.EmitMode.ON_COMPLETE, InvocationStatus.PENDING),
                new Case(
                        "ON_COMPLETE + RETRYING",
                        WorkflowInsightConfig.EmitMode.ON_COMPLETE,
                        InvocationStatus.RETRYING),
                new Case(
                        "ON_FAILURE + SUCCEEDED",
                        WorkflowInsightConfig.EmitMode.ON_FAILURE,
                        InvocationStatus.SUCCEEDED));

        for (Case scenario : cases) {
            var exporter = new CountingExporter();
            var plugin = Executions.started(environment(scenario.mode(), null, exporter), start(arn(1)));
            plugin.onInvocationEnd(end(arn(1), scenario.status()));

            assertEquals(List.of(), exporter.exported, scenario.name() + ": no record should be emitted");
            assertEquals(1, exporter.flushes.get(), scenario.name() + ": the flush must happen anyway");
        }
    }

    @Test
    void everyInvocationEndOfAWarmEnvironmentFlushesExactlyOnce() {
        var exporter = new CountingExporter();
        var environment = environment(WorkflowInsightConfig.EmitMode.ON_CHANGE, null, exporter);

        int invocations = 5;
        for (int i = 0; i < invocations; i++) {
            // A warm environment: each invocation is served by its own instance from the same factory.
            var plugin = Executions.started(environment, start(arn(i)));
            plugin.onOperationChange(change(arn(i)));
            plugin.onInvocationEnd(end(arn(i), InvocationStatus.SUCCEEDED));
            // Sequential ends have nothing to share a flush with, so the cadence bound is tight here.
            assertEquals(i + 1, exporter.flushes.get(), "one flush per invocation end, never skipped");
        }
        assertEquals(invocations, exporter.flushes.get());
        assertTrue(exporter.exported.size() >= invocations, "each execution's terminal record was exported");
    }

    @Test
    void invocationEndsThatOverlapMayShareAFlushButNoneIsLeftUnflushed() {
        var exporter = new CountingExporter();
        var environment = environment(WorkflowInsightConfig.EmitMode.ON_COMPLETE, null, exporter);

        int executions = 8;
        var barrier = new CyclicBarrier(executions);
        var done = new CountDownLatch(executions);
        for (int i = 0; i < executions; i++) {
            String executionArn = arn(100 + i);
            var plugin = Executions.started(environment, start(executionArn));
            var thread = new Thread(
                    () -> {
                        try {
                            barrier.await(60, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        plugin.onInvocationEnd(end(executionArn, InvocationStatus.SUCCEEDED));
                        done.countDown();
                    },
                    "overlapping-end-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        try {
            assertTrue(done.await(60, TimeUnit.SECONDS), "an invocation end never returned");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

        assertEquals(executions, exporter.exported.size(), "every execution's terminal record must be exported");
        assertTrue(exporter.flushes.get() >= 1, "the ends must be covered by at least one flush");
        assertTrue(
                exporter.flushes.get() <= executions,
                "at most one flush per invocation end: " + exporter.flushes.get() + " for " + executions);
        // Every record must be followed by a flush: each end's own request is served by a flush that starts after its
        // record was exported, so the last flush cannot precede the last export.
        assertEquals(
                executions,
                exporter.exportsAtFlush.get(exporter.exportsAtFlush.size() - 1),
                "the last flush ran after every terminal record: " + exporter.exportsAtFlush);
    }

    @Test
    void aSampledOutExecutionFlushesNothing() {
        // Unchanged by the move of flush onto the export pump: a sampled-out end never schedules a record, so it
        // neither drains nor flushes.
        var exporter = new CountingExporter();
        var environment = environment(WorkflowInsightConfig.EmitMode.ON_CHANGE, 0.0, exporter);

        var plugins = new ArrayList<InsightPlugin>();
        for (int i = 0; i < 10; i++) {
            var plugin = Executions.started(environment, start(arn(i)));
            plugins.add(plugin);
            plugin.onOperationChange(change(arn(i)));
            plugin.onInvocationEnd(end(arn(i), InvocationStatus.SUCCEEDED));
        }

        assertEquals(List.of(), exporter.exported);
        assertEquals(0, exporter.flushes.get(), "a sampled-out invocation end neither drains nor flushes");
        for (InsightPlugin plugin : plugins) {
            assertFalse(Executions.outstanding(plugin), "a sampled-out invocation leaves the scheduler owing nothing");
        }
    }

    private static Map<String, OperationChangeItemInfo> ops() {
        Map<String, OperationChangeItemInfo> operations = new LinkedHashMap<>();
        operations.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "greet",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        OperationStatus.SUCCEEDED,
                        1,
                        false,
                        null,
                        null));
        return operations;
    }

    private static InvocationInfo start(String executionArn) {
        return new InvocationInfo("req", executionArn, true, START, "in", ops(), Map.of());
    }

    private static OperationChangeInfo change(String executionArn) {
        return new OperationChangeInfo("req", executionArn, ops(), ops());
    }

    private static InvocationEndInfo end(String executionArn, InvocationStatus status) {
        return new InvocationEndInfo("req", executionArn, true, START, ops(), status, null, "in", "out");
    }
}
