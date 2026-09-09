// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Hand-driven hook tests for behaviors the local runner cannot easily produce deterministically: on-change emission
 * (invocation start / operation change / invocation end), cross-invocation state preservation on PENDING/RETRYING, and
 * exporter isolation + flush.
 */
class WorkflowInsightHookTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static final class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();
        int flushes;

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            flushes++;
        }
    }

    private static final class BlockingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new ArrayList<>();
        final CountDownLatch firstExportStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstExport = new CountDownLatch(1);
        final AtomicInteger exports = new AtomicInteger();
        int flushes;

        @Override
        public void export(WorkflowInsightRecord record) {
            if (exports.incrementAndGet() == 1) {
                firstExportStarted.countDown();
                await(releaseFirstExport);
            }
            records.add(record);
        }

        @Override
        public void flush() {
            flushes++;
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting for test latch");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for test latch", e);
            }
        }
    }

    private Map<String, OperationChangeItemInfo> ops(String name, OperationStatus status) {
        Map<String, OperationChangeItemInfo> m = new LinkedHashMap<>();
        m.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1", name, "STEP", "Step", null, START, START.plusMillis(5), status, 1, false, null, null));
        return m;
    }

    private InvocationInfo start(boolean first) {
        return new InvocationInfo("req", ARN, first, START, "in", ops("greet", OperationStatus.STARTED), Map.of());
    }

    private InvocationEndInfo end(InvocationStatus status, Object result, Throwable error) {
        return new InvocationEndInfo(
                "req", ARN, true, START, ops("greet", OperationStatus.SUCCEEDED), status, error, "in", result);
    }

    @Test
    void onChangeEmitsAtStartChangeAndEnd() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        plugin.onInvocationStart(start(true));
        plugin.onOperationChange(new OperationChangeInfo(
                "req", ARN, ops("greet", OperationStatus.SUCCEEDED), ops("greet", OperationStatus.SUCCEEDED)));
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null));

        assertEquals(3, exporter.records.size());
        assertEquals("RUNNING", exporter.records.get(0).status());
        assertEquals("RUNNING", exporter.records.get(1).status());
        assertEquals("SUCCEEDED", exporter.records.get(2).status());
        assertEquals(1, exporter.flushes, "ON_CHANGE flushes once after the invocation-final record");
    }

    @Test
    void onChangePreservesNoDelayBurstWithinQueueBound() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        plugin.onInvocationStart(start(true));
        for (var i = 1; i <= 10; i++) {
            var operations = ops("change-" + i, OperationStatus.SUCCEEDED);
            plugin.onOperationChange(new OperationChangeInfo("req", ARN, operations, operations));
        }
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null));

        assertEquals(12, exporter.records.size(), "start + ten changes + final are preserved without backpressure");
        assertTrue(exporter.records.subList(0, 11).stream().allMatch(r -> "RUNNING".equals(r.status())));
        assertEquals("SUCCEEDED", exporter.records.get(11).status());
        assertEquals(1, exporter.flushes);
    }

    @Test
    void onChangeCheckpointHookDoesNotWaitForExporterAndInvocationEndDrainsInOrder() throws Exception {
        var exporter = new BlockingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        plugin.onInvocationStart(start(true));
        assertTrue(exporter.firstExportStarted.await(5, TimeUnit.SECONDS), "initial export reached worker");

        var change = new OperationChangeInfo(
                "req", ARN, ops("greet", OperationStatus.SUCCEEDED), ops("greet", OperationStatus.SUCCEEDED));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> plugin.onOperationChange(change));

        var invocationEnd =
                CompletableFuture.runAsync(() -> plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null)));
        assertFalse(invocationEnd.isDone(), "invocation end waits for the blocked export lane");

        exporter.releaseFirstExport.countDown();
        invocationEnd.get(5, TimeUnit.SECONDS);

        assertEquals(
                List.of("RUNNING", "RUNNING", "SUCCEEDED"),
                exporter.records.stream().map(WorkflowInsightRecord::status).toList());
        assertEquals(1, exporter.flushes);
    }

    @Test
    void onCompleteSkipsNonTerminalAndEmitsTerminalOnly() {
        var exporter = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());

        plugin.onInvocationStart(start(true));
        plugin.onOperationChange(new OperationChangeInfo(
                "req", ARN, ops("greet", OperationStatus.SUCCEEDED), ops("greet", OperationStatus.SUCCEEDED)));
        assertTrue(exporter.records.isEmpty(), "no record before terminal in on-complete mode");
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null));
        assertEquals(1, exporter.records.size());
        assertEquals("SUCCEEDED", exporter.records.get(0).status());
    }

    @Test
    void suspendResumeKeepsStableStartTimeAndLeavesNoRetainedState() {
        var exporter = new CapturingExporter();
        var plugin = (WorkflowInsight.InsightPlugin) WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        plugin.onInvocationStart(start(true)); // first invocation
        plugin.onInvocationEnd(end(InvocationStatus.PENDING, null, null)); // suspend -> state removed
        plugin.onInvocationStart(start(false)); // resume invocation re-seeds state
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null)); // resume + terminal

        // start(RUNNING) + pending(RUNNING) + resume-start(RUNNING) + terminal(SUCCEEDED); all share the stable
        // startTime recreated from InvocationInfo.executionStartTime() across the suspend boundary.
        assertEquals(4, exporter.records.size());
        String startTime = exporter.records.get(0).startTime();
        assertTrue(exporter.records.stream().allMatch(r -> startTime.equals(r.startTime())));
        assertEquals(START.toString(), startTime);
        assertEquals(0, plugin.retainedStateCount(), "no per-execution state retained after invocation end");
    }

    @Test
    void exporterFailureIsIsolatedAndOthersStillReceiveAndFlush() {
        var throwing = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                throw new RuntimeException("exporter down");
            }
        };
        var good = new CapturingExporter();
        DurableExecutionPlugin plugin = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .addExporter(throwing)
                .addExporter(good)
                .build());

        plugin.onInvocationStart(start(true));
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null));

        assertEquals(1, good.records.size(), "failing exporter never blocks the others");
        assertFalse(good.flushes == 0, "surviving exporter is flushed");
    }
}
