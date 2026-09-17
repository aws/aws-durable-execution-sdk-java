// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * One plugin instance — and therefore one {@link ExportScheduler} — serves a whole execution environment, and an
 * environment can host several durable executions at once (routine under Lambda Managed Instances). These tests pin the
 * per-execution guarantees that concurrency demands: one execution's record never displaces another's, and each
 * execution's drain returns only after its own record reached the exporters.
 */
class ConcurrentExecutionsExportTest {

    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static String arn(int index) {
        return "arn:aws:lambda:us-west-2:111122223333:function:f:$LATEST/durable-execution/exec-" + index + "/inv-1";
    }

    private static class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }

        /** Identity, not equality: these tests track the exact record instance an execution scheduled. */
        boolean exported(WorkflowInsightRecord record) {
            for (WorkflowInsightRecord seen : records) {
                if (seen == record) {
                    return true;
                }
            }
            return false;
        }
    }

    private static WorkflowInsightRecord record(String executionArn, String status) {
        var r = new WorkflowInsightRecord();
        r.executionArn = executionArn;
        r.status = status;
        return r;
    }

    private static ExportScheduler scheduler(List<Throwable> failures, InsightExporter... exporters) {
        return new ExportScheduler(List.of(exporters), (rec, exp) -> exp.export(rec), failures::add, workers());
    }

    private static Executor workers() {
        return command -> new Thread(command, "test-export-worker").start();
    }

    /**
     * Parks the pump task and then reports rejection, leaving the scheduler idle with the record still queued. Nothing
     * about the scheduler is faked: the parked task is its own {@code () -> pump(handle)} lambda, run later verbatim.
     */
    private static final class ParkingExecutor implements Executor {
        final Deque<Runnable> parked = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            parked.add(command);
            throw new RejectedExecutionException("test: parked, reported as rejected");
        }
    }

    /** Terminal records, by execution ARN, in the order the exporter received them. */
    private static List<String> terminalArns(CapturingExporter exporter) {
        List<String> out = new ArrayList<>();
        for (WorkflowInsightRecord r : exporter.records) {
            if ("SUCCEEDED".equals(r.status())) {
                out.add(r.executionArn());
            }
        }
        return out;
    }

    @Test
    void everyConcurrentExecutionDeliversItsTerminalRecordExactlyOnce() throws Exception {
        int executions = 10;
        int changesEach = 3;
        var failures = new CopyOnWriteArrayList<Throwable>();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(failures, exporter);

        var barrier = new CyclicBarrier(executions);
        // Executions whose drain returned before their own terminal record had reached the exporter. Each thread checks
        // its own postcondition the instant its drain returns; inspecting the exporter only after joining every thread
        // would also pass if a drain returned early and the export landed a moment later.
        var returnedBeforeExport = Collections.synchronizedList(new ArrayList<String>());
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < executions; i++) {
            String executionArn = arn(i);
            var thread = new Thread(
                    () -> {
                        awaitBarrier(barrier);
                        for (int c = 0; c < changesEach; c++) {
                            scheduler.schedule(executionArn, record(executionArn, "RUNNING"));
                        }
                        WorkflowInsightRecord terminal = record(executionArn, "SUCCEEDED");
                        scheduler.schedule(executionArn, terminal);
                        scheduler.drain(executionArn);
                        // This thread is the only one scheduling for this ARN, so no later record can supersede the
                        // terminal one: once drain returns, it must already have reached the exporter.
                        if (!exporter.exported(terminal)) {
                            returnedBeforeExport.add(executionArn);
                        }
                    },
                    "execution-" + i);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(30_000);
            assertFalse(thread.isAlive(), "every execution's drain returned");
        }

        assertEquals(
                List.of(),
                returnedBeforeExport,
                "drain returned before this execution's own terminal record reached the exporter");
        List<String> delivered = terminalArns(exporter);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < executions; i++) {
            expected.add(arn(i));
        }
        assertEquals(expected, new HashSet<>(delivered), "no execution lost its terminal record");
        assertEquals(executions, delivered.size(), "and none was exported twice");
        assertTrue(failures.isEmpty(), "no scheduler failure was reported: " + failures);
    }

    /**
     * Regression: a pump that is exiting must not complete the drain signal of an execution whose record it does not
     * own. Such a record has already left the queue — it is inside the exporters — so "no record queued for this ARN"
     * is not enough to call the signal orphaned. If it were, the exiting pump would release {@code drain(arn)} mid
     * export and the invocation could return before its final record was delivered.
     *
     * <p>The state is built through the {@link Executor} seam rather than by racing threads: the executor parks the
     * pump task and reports rejection, which is the same shape the scheduler produces on its own in the window between
     * the pump loop's return to idle and its {@code finally} — a live pump whose handle is no longer the installed one.
     */
    @Test
    void anExitingPumpDoesNotReleaseADrainWhoseRecordIsStillInsideTheExporter() throws Exception {
        var executor = new ParkingExecutor();
        var exporting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Set<WorkflowInsightRecord> exported = ConcurrentHashMap.newKeySet();
        var scheduler = new ExportScheduler(
                List.of(record -> {}),
                (rec, exp) -> {
                    exporting.countDown();
                    await(release, 10);
                    exported.add(rec);
                },
                new CopyOnWriteArrayList<Throwable>()::add,
                executor);

        String executionArn = arn(1);
        WorkflowInsightRecord terminal = record(executionArn, "SUCCEEDED");
        scheduler.schedule(executionArn, terminal);
        assertEquals(1, executor.parked.size(), "the pump task was parked, so the record is still queued");

        // A drainer picks the record up on the inline path and is now inside the exporter.
        var inlineDrained = new CountDownLatch(1);
        var inlineDrainer = new Thread(
                () -> {
                    scheduler.drain(executionArn);
                    inlineDrained.countDown();
                },
                "inline-drainer");
        inlineDrainer.setDaemon(true);
        inlineDrainer.start();
        assertTrue(exporting.await(5, TimeUnit.SECONDS), "the terminal record is inside the exporter");

        // Now let the parked pump run to completion. It finds nothing queued and exits; its cleanup must leave the
        // record that is mid-export alone.
        executor.parked.poll().run();

        var secondDrained = new CountDownLatch(1);
        var secondDrainer = new Thread(
                () -> {
                    scheduler.drain(executionArn);
                    secondDrained.countDown();
                },
                "second-drainer");
        secondDrainer.setDaemon(true);
        secondDrainer.start();
        assertFalse(
                secondDrained.await(500, TimeUnit.MILLISECONDS),
                "drain returned while the execution's record was still inside the exporter");
        assertTrue(exported.isEmpty(), "the exporter has not finished with the record yet");

        release.countDown();
        assertTrue(secondDrained.await(5, TimeUnit.SECONDS), "the drain returns once the export completes");
        assertTrue(inlineDrained.await(5, TimeUnit.SECONDS), "so does the drain that ran the export");
        assertEquals(Set.of(terminal), exported, "the terminal record was exported exactly once");
    }

    @Test
    void aRecordForAnotherExecutionNeverDisplacesAPendingTerminalRecord() throws Exception {
        String slowExecution = arn(1);
        String otherExecution = arn(2);
        var exporting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exporter = new CapturingExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                super.export(record);
                if (records.size() == 1) {
                    exporting.countDown();
                    await(release);
                }
            }
        };
        var scheduler = scheduler(new CopyOnWriteArrayList<>(), exporter);

        // One execution's export is in flight and blocked...
        scheduler.schedule(slowExecution, record(slowExecution, "RUNNING"));
        assertTrue(exporting.await(5, TimeUnit.SECONDS), "the first export is in flight");
        // ...while a second execution's terminal record is queued, followed by an update for the first execution.
        // The first execution's own update must coalesce only with its own slot, never over the second execution's.
        scheduler.schedule(otherExecution, record(otherExecution, "SUCCEEDED"));
        scheduler.schedule(slowExecution, record(slowExecution, "SUCCEEDED"));

        var seenBySlowDrain = Collections.synchronizedList(new ArrayList<String>());
        var seenByOtherDrain = Collections.synchronizedList(new ArrayList<String>());
        var slowDrained = new CountDownLatch(1);
        var otherDrained = new CountDownLatch(1);
        var slowDrainer = new Thread(
                () -> {
                    scheduler.drain(slowExecution);
                    seenBySlowDrain.addAll(terminalArns(exporter));
                    slowDrained.countDown();
                },
                "slow-drainer");
        var otherDrainer = new Thread(
                () -> {
                    scheduler.drain(otherExecution);
                    seenByOtherDrain.addAll(terminalArns(exporter));
                    otherDrained.countDown();
                },
                "other-drainer");
        slowDrainer.start();
        otherDrainer.start();

        assertFalse(slowDrained.await(200, TimeUnit.MILLISECONDS), "a drain cannot return while its record is pending");
        assertFalse(otherDrained.await(50, TimeUnit.MILLISECONDS), "nor can the other execution's drain");

        release.countDown();
        assertTrue(slowDrained.await(5, TimeUnit.SECONDS), "the blocked execution's drain completes");
        assertTrue(otherDrained.await(5, TimeUnit.SECONDS), "the other execution's drain completes");

        assertTrue(
                seenByOtherDrain.contains(otherExecution),
                "drain returned only after this execution's own terminal record was exported");
        assertTrue(
                seenBySlowDrain.contains(slowExecution),
                "drain returned only after this execution's own terminal record was exported");
        List<String> delivered = terminalArns(exporter);
        assertEquals(
                Set.of(slowExecution, otherExecution),
                new HashSet<>(delivered),
                "neither execution's terminal record was lost");
        assertEquals(2, delivered.size(), "and neither was exported twice");
    }

    @Test
    void concurrentExecutionsDrivenThroughThePluginHooksAllDeliverTheirTerminalRecord() throws Exception {
        int executions = 5;
        var exporter = new CapturingExporter();
        var plugin = (WorkflowInsight.InsightPlugin) WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());

        var barrier = new CyclicBarrier(executions);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < executions; i++) {
            String executionArn = arn(i);
            var thread = new Thread(
                    () -> {
                        awaitBarrier(barrier);
                        plugin.onInvocationStart(start(executionArn));
                        for (int c = 0; c < 3; c++) {
                            plugin.onOperationChange(new OperationChangeInfo(
                                    "req",
                                    executionArn,
                                    ops(OperationStatus.SUCCEEDED),
                                    ops(OperationStatus.SUCCEEDED)));
                        }
                        plugin.onInvocationEnd(end(executionArn, InvocationStatus.SUCCEEDED));
                    },
                    "execution-" + i);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(30_000);
            assertFalse(thread.isAlive(), "every invocation-end hook returned");
        }

        List<String> delivered = terminalArns(exporter);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < executions; i++) {
            expected.add(arn(i));
        }
        assertEquals(expected, new HashSet<>(delivered), "every execution's terminal record arrived");
        assertEquals(executions, delivered.size(), "and none arrived twice");
        assertEquals(0, plugin.retainedStateCount(), "no per-execution state retained after invocation end");
    }

    private static Map<String, OperationChangeItemInfo> ops(OperationStatus status) {
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
                        status,
                        1,
                        false,
                        null,
                        null));
        return operations;
    }

    private static InvocationInfo start(String executionArn) {
        return new InvocationInfo("req", executionArn, true, START, "in", ops(OperationStatus.STARTED), Map.of());
    }

    private static InvocationEndInfo end(String executionArn, InvocationStatus status) {
        return new InvocationEndInfo(
                "req", executionArn, true, START, ops(OperationStatus.SUCCEEDED), status, null, "in", "out");
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        await(latch, 5);
    }

    private static void await(CountDownLatch latch, long timeoutSeconds) {
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
