// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Contract tests for {@link ExportScheduler}: serial exports, latest-wins coalescing, drain, and exporter fan-out. */
class ExportSchedulerTest {

    /** Runs submitted tasks only when the test asks, so pump timing is fully controlled. */
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        int submissions;
        boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("no worker");
            }
            submissions++;
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static class CapturingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> records = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
            threads.add(Thread.currentThread());
        }
    }

    private static WorkflowInsightRecord record(String status) {
        var r = new WorkflowInsightRecord();
        r.status = status;
        return r;
    }

    private static ExportScheduler scheduler(
            Executor executor, List<Throwable> failures, InsightExporter... exporters) {
        return new ExportScheduler(List.of(exporters), (rec, exp) -> exp.export(rec), failures::add, executor);
    }

    @Test
    void scheduleHandsTheRecordToAWorkerRatherThanExportingOnTheCallingThread() {
        var executor = new ManualExecutor();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(executor, new ArrayList<>(), exporter);

        scheduler.schedule(record("RUNNING"));
        assertTrue(exporter.records.isEmpty(), "nothing exported until a worker runs");

        executor.runAll();
        assertEquals(1, exporter.records.size());
        assertEquals("RUNNING", exporter.records.get(0).status());
    }

    @Test
    void updatesScheduledBeforeTheWorkerRunsCollapseIntoTheLatestRecord() {
        var executor = new ManualExecutor();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(executor, new ArrayList<>(), exporter);

        scheduler.schedule(record("r1"));
        scheduler.schedule(record("r2"));
        scheduler.schedule(record("r3"));
        executor.runAll();

        assertEquals(1, exporter.records.size(), "one pump, one latest record");
        assertEquals("r3", exporter.records.get(0).status());
        assertEquals(1, executor.submissions, "only one worker was ever requested");
    }

    @Test
    void recordScheduledAfterAPumpFinishesStartsANewPump() {
        var executor = new ManualExecutor();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(executor, new ArrayList<>(), exporter);

        scheduler.schedule(record("first"));
        executor.runAll();
        scheduler.schedule(record("second"));
        executor.runAll();

        assertEquals(List.of("first", "second"), statuses(exporter));
    }

    @Test
    void updatesArrivingWhileAnExportIsInFlightAreCoalescedAndExportedAfterIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exporter = new CapturingExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                super.export(record);
                if (records.size() == 1) {
                    entered.countDown();
                    await(release);
                }
            }
        };
        var scheduler = scheduler(sharedWorkers(), new ArrayList<>(), exporter);

        scheduler.schedule(record("first"));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "first export is in flight");
        scheduler.schedule(record("dropped-1"));
        scheduler.schedule(record("dropped-2"));
        scheduler.schedule(record("final"));
        release.countDown();
        scheduler.drain();

        assertEquals(List.of("first", "final"), statuses(exporter));
    }

    @Test
    void drainReturnsImmediatelyWhenIdle() {
        var scheduler = scheduler(new ManualExecutor(), new ArrayList<>(), new CapturingExporter());
        scheduler.drain();
        scheduler.drain();
    }

    @Test
    void drainWaitsForTheInFlightExportAndEveryPendingRecord() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exporter = new CapturingExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                super.export(record);
                if (records.size() == 1) {
                    entered.countDown();
                    await(release);
                }
            }
        };
        var scheduler = scheduler(sharedWorkers(), new ArrayList<>(), exporter);

        scheduler.schedule(record("slow"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        scheduler.schedule(record("final"));

        var drained = new CountDownLatch(1);
        var drainer = new Thread(() -> {
            scheduler.drain();
            drained.countDown();
        });
        drainer.start();
        assertFalse(drained.await(200, TimeUnit.MILLISECONDS), "drain blocks while an export is in flight");

        release.countDown();
        assertTrue(drained.await(5, TimeUnit.SECONDS), "drain completes once the queue is empty");
        assertEquals(List.of("slow", "final"), statuses(exporter));
    }

    @Test
    void exportersRunOffTheSchedulingThread() {
        var exporter = new CapturingExporter();
        var scheduler = scheduler(sharedWorkers(), new ArrayList<>(), exporter);

        scheduler.schedule(record("RUNNING"));
        scheduler.drain();

        assertEquals(1, exporter.threads.size());
        assertNotSame(Thread.currentThread(), exporter.threads.get(0));
    }

    @Test
    void aFailingExporterNeverBlocksTheOthersForTheSameRecord() {
        var failures = new CopyOnWriteArrayList<Throwable>();
        var good = new CapturingExporter();
        InsightExporter bad = record -> {
            throw new AssertionError("exporter blew up");
        };
        var scheduler = scheduler(sharedWorkers(), failures, bad, good);

        scheduler.schedule(record("RUNNING"));
        scheduler.drain();

        assertEquals(1, good.records.size());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof AssertionError);
    }

    @Test
    void exportersForOneRecordRunConcurrentlySoASlowExporterDoesNotDelayTheOthers() throws Exception {
        var release = new CountDownLatch(1);
        var fast = new CapturingExporter();
        InsightExporter slow = record -> await(release);
        var scheduler = scheduler(sharedWorkers(), new ArrayList<>(), slow, fast);

        scheduler.schedule(record("RUNNING"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fast.records.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, fast.records.size(), "fast exporter received the record while the slow one is still blocked");

        release.countDown();
        scheduler.drain();
    }

    @Test
    void drainExportsThePendingRecordInlineWhenNoWorkerCouldBeStarted() {
        var executor = new ManualExecutor();
        executor.reject = true;
        var failures = new ArrayList<Throwable>();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(executor, failures, exporter);

        scheduler.schedule(record("final"));
        assertTrue(exporter.records.isEmpty(), "the hook thread does not export");
        assertEquals(1, failures.size(), "the worker failure is reported");

        scheduler.drain();

        assertEquals(List.of("final"), statuses(exporter));
        assertSame(Thread.currentThread(), exporter.threads.get(0), "the invocation boundary delivers it");
    }

    @Test
    void aLaterScheduleRetriesTheWorkerAfterARejection() {
        var executor = new ManualExecutor();
        executor.reject = true;
        var exporter = new CapturingExporter();
        var scheduler = scheduler(executor, new ArrayList<>(), exporter);

        scheduler.schedule(record("older"));
        executor.reject = false;
        scheduler.schedule(record("newer"));
        executor.runAll();

        assertEquals(List.of("newer"), statuses(exporter), "the retry exports the latest record");
        scheduler.drain();
        assertEquals(1, exporter.records.size());
    }

    private static List<String> statuses(CapturingExporter exporter) {
        List<String> out = new ArrayList<>();
        for (WorkflowInsightRecord r : exporter.records) {
            out.add(r.status());
        }
        return out;
    }

    private static Executor sharedWorkers() {
        return command -> new Thread(command, "test-export-worker").start();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
