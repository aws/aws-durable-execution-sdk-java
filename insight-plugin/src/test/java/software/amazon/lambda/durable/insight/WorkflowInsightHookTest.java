// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
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

    private static class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        volatile int flushes;

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
            threads.add(Thread.currentThread());
        }

        @Override
        public void flush() {
            flushes++;
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

    /** The environment one or more invocations are then served in: one factory, one scheduler, one exporter set. */
    private static DurableExecutionPluginFactory onChangeEnvironment(InsightExporter exporter) {
        return WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());
    }

    @Test
    void onChangeEmitsAtStartChangeAndEnd() {
        var exporter = new CapturingExporter();
        var plugin = Executions.started(onChangeEnvironment(exporter), start(true));

        // Let each scheduled export land before the next hook so all three snapshots are observable; back-to-back
        // hooks may otherwise coalesce into the latest record (covered separately below).
        plugin.drainExports();
        plugin.onOperationChange(new OperationChangeInfo(
                "req", ARN, ops("greet", OperationStatus.SUCCEEDED), ops("greet", OperationStatus.SUCCEEDED)));
        plugin.drainExports();
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null));

        assertEquals(3, exporter.records.size());
        assertEquals("RUNNING", exporter.records.get(0).status());
        assertEquals("RUNNING", exporter.records.get(1).status());
        assertEquals("SUCCEEDED", exporter.records.get(2).status());
        assertEquals(1, exporter.flushes, "exporters are flushed once, at invocation end");
    }

    @Test
    void onChangeExportsOffTheHookThreadAndCoalescesBurstsIntoTheLatestRecord() {
        var exporter = new CapturingExporter();
        var plugin = Executions.started(onChangeEnvironment(exporter), start(true));

        for (int i = 0; i < 20; i++) {
            plugin.onOperationChange(new OperationChangeInfo(
                    "req", ARN, ops("greet", OperationStatus.SUCCEEDED), ops("greet", OperationStatus.SUCCEEDED)));
        }
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null));

        // Intermediate RUNNING snapshots may be superseded while an export is in flight, but the final record is always
        // delivered, always last, and no export ever ran on the thread that delivered the hooks.
        assertFalse(exporter.records.isEmpty());
        assertTrue(exporter.records.size() <= 22, "no duplicate exports");
        var last = exporter.records.get(exporter.records.size() - 1);
        assertEquals("SUCCEEDED", last.status());
        assertTrue(exporter.records.subList(0, exporter.records.size() - 1).stream()
                .allMatch(r -> "RUNNING".equals(r.status())));
        assertTrue(exporter.threads.stream().noneMatch(t -> t == Thread.currentThread()));
        assertEquals(1, exporter.flushes);
    }

    @Test
    void changeHookArrivingWhileTheEndRecordDrainsCannotFollowOrReplaceIt() throws Exception {
        var exportingFinal = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exporter = new CapturingExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                super.export(record);
                if ("SUCCEEDED".equals(record.status())) {
                    exportingFinal.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
            }
        };
        var plugin = Executions.started(onChangeEnvironment(exporter), start(true));
        plugin.drainExports();

        // The end hook blocks in its drain while the final record is being exported; the change hook arrives then,
        // as it does when a checkpoint for an unawaited asynchronous operation completes during invocation end.
        var ending = new Thread(() -> plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null)));
        ending.start();
        assertTrue(exportingFinal.await(5, TimeUnit.SECONDS));
        plugin.onOperationChange(new OperationChangeInfo(
                "req", ARN, ops("greet", OperationStatus.SUCCEEDED), ops("greet", OperationStatus.SUCCEEDED)));
        release.countDown();
        ending.join(5_000);
        assertFalse(ending.isAlive());

        assertEquals(2, exporter.records.size(), "start snapshot + final record; the late change is dropped");
        assertEquals("RUNNING", exporter.records.get(0).status());
        assertEquals("SUCCEEDED", exporter.records.get(1).status());
        assertEquals(1, exporter.flushes);
    }

    @Test
    void invocationEndFlushesExportersEvenWhenNothingWasEmitted() {
        var exporter = new CapturingExporter();
        var plugin = Executions.started(
                WorkflowInsight.workflowInsight(
                        WorkflowInsightConfig.builder().addExporter(exporter).build()),
                start(true));
        plugin.onInvocationEnd(end(InvocationStatus.PENDING, null, null));

        assertTrue(exporter.records.isEmpty(), "on-complete emits nothing for a suspend");
        assertEquals(1, exporter.flushes, "the invocation boundary still flushes buffered exporters");
    }

    @Test
    void onCompleteSkipsNonTerminalAndEmitsTerminalOnly() {
        var exporter = new CapturingExporter();
        var plugin = Executions.started(
                WorkflowInsight.workflowInsight(
                        WorkflowInsightConfig.builder().addExporter(exporter).build()),
                start(true));
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
        var environment = onChangeEnvironment(exporter);

        // The suspend and the resume are two invocations of the same execution in one warm environment, so the SDK
        // serves them with two instances: nothing is carried over in the plugin, and nothing has to be cleaned up.
        var first = Executions.started(environment, start(true));
        first.drainExports();
        first.onInvocationEnd(end(InvocationStatus.PENDING, null, null)); // suspend
        var resumed = Executions.started(environment, start(false)); // resume re-seeds from its own InvocationInfo
        resumed.drainExports();
        resumed.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null)); // resume + terminal

        // start(RUNNING) + pending(RUNNING) + resume-start(RUNNING) + terminal(SUCCEEDED); all share the stable
        // startTime recreated from InvocationInfo.executionStartTime() across the suspend boundary.
        assertEquals(4, exporter.records.size());
        String startTime = exporter.records.get(0).startTime();
        assertTrue(exporter.records.stream().allMatch(r -> startTime.equals(r.startTime())));
        assertEquals(START.toString(), startTime);
        assertFalse(Executions.outstanding(first), "the suspended invocation left the scheduler owing nothing");
        assertFalse(Executions.outstanding(resumed), "nor did the resumed one");
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
        var plugin = Executions.started(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                        .addExporter(throwing)
                        .addExporter(good)
                        .build()),
                start(true));
        plugin.onInvocationEnd(end(InvocationStatus.SUCCEEDED, "out", null));

        assertEquals(1, good.records.size(), "failing exporter never blocks the others");
        assertFalse(good.flushes == 0, "surviving exporter is flushed");
    }
}
