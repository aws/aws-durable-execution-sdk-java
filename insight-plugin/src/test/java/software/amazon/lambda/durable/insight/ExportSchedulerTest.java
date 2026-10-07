// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
        return record("arn:exec-a", status);
    }

    private static WorkflowInsightRecord record(String executionArn, String status) {
        var r = new WorkflowInsightRecord();
        r.executionArn = executionArn;
        r.status = status;
        return r;
    }

    @Test
    void recordsOfDifferentExecutionsNeverDisplaceEachOther() {
        var executor = new ManualExecutor();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(executor, new ArrayList<>(), exporter);

        scheduler.schedule(record("arn:exec-a", "a-final"));
        scheduler.schedule(record("arn:exec-b", "b-running"));
        executor.runAll();

        assertEquals(List.of("a-final", "b-running"), statuses(exporter));
    }

    @Test
    void coalescingStaysWithinOneExecutionAndExecutionsAreServedInFirstPendingOrder() {
        var executor = new ManualExecutor();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(executor, new ArrayList<>(), exporter);

        scheduler.schedule(record("arn:exec-a", "a1"));
        scheduler.schedule(record("arn:exec-b", "b1"));
        scheduler.schedule(record("arn:exec-a", "a2")); // supersedes a1 but keeps a's place ahead of b
        scheduler.schedule(record("arn:exec-b", "b2"));
        scheduler.schedule(record("arn:exec-c", "c1"));
        executor.runAll();

        assertEquals(List.of("a2", "b2", "c1"), statuses(exporter));
    }

    @Test
    void anotherExecutionsUpdateWhileAnExportIsInFlightCannotDropAPendingFinalRecord() throws Exception {
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

        scheduler.schedule(record("arn:exec-a", "a-running"));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "a's first export is in flight");
        scheduler.schedule(record("arn:exec-a", "a-final"));
        scheduler.schedule(record("arn:exec-b", "b-running"));
        scheduler.schedule(record("arn:exec-b", "b-final"));
        release.countDown();
        scheduler.drain();

        assertEquals(List.of("a-running", "a-final", "b-final"), statuses(exporter));
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

    @Test
    void aDrainThatObservedTheHandleBeforeTheWorkerWasRejectedStillCompletesInline() throws Exception {
        var submitted = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        Executor blockingRejector = command -> {
            submitted.countDown();
            await(proceed);
            throw new RejectedExecutionException("no worker");
        };
        var failures = new CopyOnWriteArrayList<Throwable>();
        var exporter = new CapturingExporter();
        var scheduler = scheduler(blockingRejector, failures, exporter);

        var scheduling = new Thread(() -> scheduler.schedule(record("final")), "scheduling");
        scheduling.start();
        assertTrue(submitted.await(5, TimeUnit.SECONDS), "the pump handle is published before execute rejects");

        var drained = new CountDownLatch(1);
        var drainer = new Thread(
                () -> {
                    scheduler.drain();
                    drained.countDown();
                },
                "drainer");
        drainer.start();
        Thread.sleep(100); // let the drainer observe the in-flight handle and block on it

        proceed.countDown();
        scheduling.join(5_000);
        assertTrue(drained.await(5, TimeUnit.SECONDS), "the rejected handle is completed, so the drainer wakes up");
        assertEquals(List.of("final"), statuses(exporter));
        assertEquals("drainer", exporter.threads.get(0).getName(), "the drainer exports the pending record inline");
        assertEquals(1, failures.size());
    }

    @Test
    void flushAllRunsExporterFlushesConcurrentlySoASlowFlushDoesNotDelayTheOthers() throws Exception {
        var release = new CountDownLatch(1);
        var fastFlushed = new CountDownLatch(1);
        var slow = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                await(release);
            }
        };
        var fast = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                fastFlushed.countDown();
            }
        };
        var scheduler = scheduler(sharedWorkers(), new ArrayList<>(), slow, fast);

        var flushed = new CountDownLatch(1);
        new Thread(() -> {
                    scheduler.flushAll();
                    flushed.countDown();
                })
                .start();

        assertTrue(fastFlushed.await(5, TimeUnit.SECONDS), "fast exporter flushed while the slow one is blocked");
        assertFalse(flushed.await(100, TimeUnit.MILLISECONDS), "flushAll waits for every exporter");
        release.countDown();
        assertTrue(flushed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void flushAllIsolatesAFailingFlush() {
        var failures = new CopyOnWriteArrayList<Throwable>();
        var flushed = new CountDownLatch(1);
        var bad = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                throw new IllegalStateException("flush failed");
            }
        };
        var good = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                flushed.countDown();
            }
        };
        var scheduler = scheduler(sharedWorkers(), failures, bad, good);

        scheduler.flushAll();

        assertEquals(0, flushed.getCount(), "the healthy exporter still flushed");
        assertEquals(1, failures.size());
    }

    @Test
    void drainReturnsAfterTheWaitWhenAnExportHangsAndTheExportStillCompletesLater() throws Exception {
        var release = new CountDownLatch(1);
        var exported = new CountDownLatch(1);
        var flushes = new AtomicInteger();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var hanging = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                await(release);
                exported.countDown();
            }

            @Override
            public void flush() {
                flushes.incrementAndGet();
            }
        };
        var scheduler = new ExportScheduler(
                List.of(hanging),
                (rec, exp) -> exp.export(rec),
                failures::add,
                sharedWorkers(),
                Duration.ofMillis(200));

        scheduler.schedule(record("final"));
        long start = System.nanoTime();
        scheduler.drain();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis < 4_000, "drain returned after the bound, not after the export: " + elapsedMillis);
        assertEquals(1, failures.size(), "the abandoned wait is reported");
        assertTrue(failures.get(0) instanceof TimeoutException, failures.get(0).toString());
        assertEquals(1, exported.getCount(), "the export is still running on its worker");

        scheduler.flushAll();
        assertEquals(0, flushes.get(), "flush never overlaps the exporter's own in-flight export");
        assertEquals(2, failures.size(), "the skipped flush is reported");

        release.countDown();
        assertTrue(exported.await(5, TimeUnit.SECONDS), "the record is still delivered once the destination answers");
        scheduler.drain();
        scheduler.flushAll();
        assertEquals(1, flushes.get(), "flush runs once the export has settled");
    }

    @Test
    void drainWithinTheWaitDeliversEverythingAndReportsNothing() {
        var exporter = new CapturingExporter();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var scheduler = new ExportScheduler(
                List.of(exporter),
                (rec, exp) -> exp.export(rec),
                failures::add,
                sharedWorkers(),
                Duration.ofSeconds(5));

        scheduler.schedule(record("a"));
        scheduler.schedule(record("arn:exec-b", "b"));
        scheduler.drain();

        assertEquals(2, exporter.records.size());
        assertTrue(failures.isEmpty());
    }

    @Test
    void flushAllReturnsAfterTheWaitWhenOneFlushHangs() throws Exception {
        var release = new CountDownLatch(1);
        var fastFlushed = new CountDownLatch(1);
        var failures = new CopyOnWriteArrayList<Throwable>();
        var slow = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                await(release);
            }
        };
        var fast = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                fastFlushed.countDown();
            }
        };
        var scheduler = new ExportScheduler(
                List.of(slow, fast),
                (rec, exp) -> exp.export(rec),
                failures::add,
                sharedWorkers(),
                Duration.ofMillis(200));

        scheduler.flushAll();

        assertEquals(0, fastFlushed.getCount(), "the healthy exporter flushed");
        assertEquals(1, failures.size(), "only the hanging flush is reported");
        assertTrue(failures.get(0) instanceof TimeoutException, failures.get(0).toString());
        release.countDown();
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
