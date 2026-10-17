// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the exporter-facing flush guarantee: {@code flush()} is served by the export pump, so it is never
 * called concurrently with {@code export()} on the same plugin instance, and cannot be starved by a queue that keeps
 * receiving records.
 *
 * <p>The cadence itself — at most one flush per request, requests that overlap sharing one flush — is covered by
 * {@link ExportSchedulerFlushCoalescingTest}.
 */
class ExportSchedulerFlushSerializationTest {

    /** Longest a flush may take to be served before the property under test is considered broken. */
    private static final long FLUSH_DEADLINE_MILLIS = 2_000;

    private static String arn(int index) {
        return "arn:aws:lambda:us-west-2:111122223333:function:f:$LATEST/durable-execution/exec-" + index + "/inv-1";
    }

    private static WorkflowInsightRecord record(String executionArn, String status) {
        var r = new WorkflowInsightRecord();
        r.executionArn = executionArn;
        r.status = status;
        return r;
    }

    private static ExportScheduler scheduler(
            Executor executor, List<Throwable> failures, InsightExporter... exporters) {
        return new ExportScheduler(List.of(exporters), (rec, exp) -> exp.export(rec), failures::add, executor);
    }

    /** Unbounded thread-per-task executor, like the cached pool the plugin injects in production. */
    private static Executor sharedWorkers() {
        return command -> {
            var thread = new Thread(command, "test-export-worker");
            thread.setDaemon(true);
            thread.start();
        };
    }

    /**
     * Records, per exporter instance, whether an {@code export()} and a {@code flush()} were ever inside the exporter
     * at the same time, and how many of each ran concurrently.
     */
    private static final class OverlapProbeExporter implements InsightExporter {
        private final long exportMillis;
        private final long flushMillis;
        final AtomicInteger inExport = new AtomicInteger();
        final AtomicInteger inFlush = new AtomicInteger();
        final AtomicInteger maxConcurrentExports = new AtomicInteger();
        final AtomicInteger maxConcurrentFlushes = new AtomicInteger();
        final AtomicInteger flushes = new AtomicInteger();
        final List<String> exported = new CopyOnWriteArrayList<>();
        final AtomicBoolean overlapped = new AtomicBoolean();

        OverlapProbeExporter(long exportMillis, long flushMillis) {
            this.exportMillis = exportMillis;
            this.flushMillis = flushMillis;
        }

        @Override
        public void export(WorkflowInsightRecord record) {
            trackMax(maxConcurrentExports, inExport.incrementAndGet());
            try {
                checkOverlap();
                sleep(exportMillis);
                checkOverlap();
                exported.add(record.status() + "@" + record.executionArn());
            } finally {
                inExport.decrementAndGet();
            }
        }

        @Override
        public void flush() {
            trackMax(maxConcurrentFlushes, inFlush.incrementAndGet());
            try {
                checkOverlap();
                sleep(flushMillis);
                checkOverlap();
                flushes.incrementAndGet();
            } finally {
                inFlush.decrementAndGet();
            }
        }

        private void checkOverlap() {
            if (inExport.get() > 0 && inFlush.get() > 0) {
                overlapped.set(true);
            }
        }

        private static void trackMax(AtomicInteger max, int observed) {
            max.accumulateAndGet(observed, Math::max);
        }
    }

    @Test
    void aFlushNeverOverlapsAnExportEvenWithManyExecutionsEndingAtOnce() throws Exception {
        var first = new OverlapProbeExporter(15, 5);
        var second = new OverlapProbeExporter(15, 5);
        var failures = new CopyOnWriteArrayList<Throwable>();
        var scheduler = scheduler(sharedWorkers(), failures, first, second);

        int executions = 6;
        var barrier = new CyclicBarrier(executions);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < executions; i++) {
            String executionArn = arn(i);
            InsightPlugin execution = Executions.plugin(scheduler, executionArn);
            var thread = new Thread(
                    () -> {
                        awaitBarrier(barrier);
                        // What an invocation does: a few RUNNING snapshots, the terminal record, then drain + flush.
                        for (int change = 0; change < 3; change++) {
                            scheduler.schedule(execution, record(executionArn, "RUNNING"));
                        }
                        scheduler.schedule(execution, record(executionArn, "SUCCEEDED"));
                        scheduler.drain(execution);
                        scheduler.flush();
                    },
                    "invocation-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(30_000);
            assertFalse(thread.isAlive(), thread.getName() + " never returned from drain/flush");
        }

        for (OverlapProbeExporter exporter : List.of(first, second)) {
            assertFalse(exporter.overlapped.get(), "flush() ran while an export was in flight on the same exporter");
            assertEquals(1, exporter.maxConcurrentExports.get(), "exports must stay serialized");
            assertEquals(1, exporter.maxConcurrentFlushes.get(), "one flush at a time, one fan-out per batch");
            // At most one flush per invocation end, and at least one: ends that land together share a flush, so the
            // count is bounded by the number of ends rather than equal to it.
            assertTrue(exporter.flushes.get() >= 1, "every invocation end must be covered by a flush");
            assertTrue(
                    exporter.flushes.get() <= executions,
                    "at most one flush per invocation end: " + exporter.flushes.get() + " for " + executions);
            for (int i = 0; i < executions; i++) {
                assertTrue(
                        exporter.exported.contains("SUCCEEDED@" + arn(i)),
                        "terminal record of " + arn(i) + " never reached the exporter");
            }
        }
        assertTrue(failures.isEmpty(), "no failure should be reported: " + failures);
    }

    @Test
    void twoFlushRequestsQueuedTogetherShareOneFlush() throws Exception {
        var exporting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var flushes = new AtomicInteger();
        var exporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                exporting.countDown();
                await(release);
            }

            @Override
            public void flush() {
                flushes.incrementAndGet();
            }
        };
        var scheduler = scheduler(sharedWorkers(), new CopyOnWriteArrayList<>(), exporter);

        scheduler.schedule(Executions.plugin(scheduler, arn(0)), record(arn(0), "SUCCEEDED"));
        assertTrue(exporting.await(5, TimeUnit.SECONDS), "the pump is inside the exporter");

        var flushed = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            var flusher = new Thread(
                    () -> {
                        scheduler.flush();
                        flushed.countDown();
                    },
                    "flusher-" + i);
            flusher.setDaemon(true);
            flusher.start();
        }
        Thread.sleep(200); // let both requests queue up behind the in-flight export

        release.countDown();
        assertTrue(flushed.await(5, TimeUnit.SECONDS), "both flush requests must be served");
        assertEquals(
                1,
                flushes.get(),
                "two requests queued together are taken as one batch and share a single flush: both drained their own"
                        + " record before asking, so one flush covers both");
    }

    /** Distinct {@link Error} type so the test asserts on this exact failure rather than any Error. */
    private static final class FlushError extends Error {
        FlushError() {
            super("flush blew up with an Error");
        }
    }

    @Test
    void aFlushThatThrowsStillReleasesTheInvocationAndLetsTheOtherExportersFlush() throws Exception {
        var flushed = new AtomicInteger();
        var throwsException = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                throw new IllegalStateException("flush blew up");
            }
        };
        var throwsError = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                throw new FlushError();
            }
        };
        var healthy = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                flushed.incrementAndGet();
            }
        };
        var failures = new CopyOnWriteArrayList<Throwable>();
        var scheduler = scheduler(sharedWorkers(), failures, throwsException, throwsError, healthy);

        assertTrue(returnsWithin(scheduler::flush, FLUSH_DEADLINE_MILLIS), "a throwing flush stranded the invocation");

        assertEquals(1, flushed.get(), "the healthy exporter still flushed");
        assertEquals(2, failures.size(), "both failures are reported, neither escapes: " + failures);
        assertTrue(
                failures.stream().anyMatch(t -> t instanceof IllegalStateException),
                "the thrown exception is reported");
        assertTrue(failures.stream().anyMatch(t -> t instanceof FlushError), "the thrown Error is reported");
    }

    @Test
    void anErrorFromTheOnlyExportersFlushStillReleasesTheInvocation() throws Exception {
        var failures = new CopyOnWriteArrayList<Throwable>();
        var onlyExporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                throw new FlushError();
            }
        };
        var scheduler = scheduler(sharedWorkers(), failures, onlyExporter);

        assertTrue(returnsWithin(scheduler::flush, FLUSH_DEADLINE_MILLIS), "an Error from flush() stranded the caller");
        assertEquals(1, failures.size(), "the Error is reported, not propagated: " + failures);
        assertTrue(failures.get(0) instanceof FlushError);
    }

    @Test
    void aFlushIsServedWhileTheQueueKeepsReceivingRecords() throws Exception {
        var exports = new AtomicInteger();
        var flushes = new AtomicInteger();
        var exporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {
                sleep(2);
                exports.incrementAndGet();
            }

            @Override
            public void flush() {
                flushes.incrementAndGet();
            }
        };
        var scheduler = scheduler(sharedWorkers(), new CopyOnWriteArrayList<>(), exporter);

        // A producer that never lets the queue run dry: it keeps re-scheduling a fixed, rotating set of executions, so
        // `pending` stays non-empty (and bounded, since records coalesce per execution) for as long as it runs.
        var rotation = new ArrayList<InsightPlugin>();
        for (int i = 0; i < 50; i++) {
            rotation.add(Executions.plugin(scheduler, arn(i)));
        }
        var stop = new AtomicBoolean();
        var scheduled = new AtomicInteger();
        var producing = new CountDownLatch(1);
        var producer = new Thread(
                () -> {
                    int index = 0;
                    while (!stop.get()) {
                        InsightPlugin execution = rotation.get(index++ % rotation.size());
                        scheduler.schedule(execution, record(execution.executionArn, "RUNNING"));
                        scheduled.incrementAndGet();
                        producing.countDown();
                    }
                },
                "record-producer");
        producer.setDaemon(true);
        producer.start();
        int exportsBefore;
        int scheduledBefore;
        boolean served;
        var exportsWhenServed = new AtomicInteger();
        var scheduledWhenServed = new AtomicInteger();
        var flushReturned = new CountDownLatch(1);
        try {
            assertTrue(producing.await(5, TimeUnit.SECONDS), "the producer never started scheduling");
            exportsBefore = exports.get();
            scheduledBefore = scheduled.get();

            // On its own thread with a deadline: a starved flush must fail this test, not hang it.
            var flusher = new Thread(
                    () -> {
                        scheduler.flush();
                        exportsWhenServed.set(exports.get());
                        scheduledWhenServed.set(scheduled.get());
                        flushReturned.countDown();
                    },
                    "flusher");
            flusher.setDaemon(true);
            flusher.start();
            served = flushReturned.await(FLUSH_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        } finally {
            // Stop the producer before asserting, so a starved flush is released and its thread does not leak.
            stop.set(true);
            producer.join(10_000);
        }

        assertTrue(
                served,
                "the flush was not served within " + FLUSH_DEADLINE_MILLIS + " ms while the queue kept receiving"
                        + " records; it must be served between records rather than after the queue drains");
        assertTrue(flushReturned.await(5, TimeUnit.SECONDS));
        assertEquals(1, flushes.get(), "the flush was served exactly once");
        assertTrue(
                exportsWhenServed.get() > exportsBefore, "the pump kept exporting: the flush did not stall the queue");
        assertTrue(
                scheduledWhenServed.get() > scheduledBefore,
                "the queue was still receiving records when the flush was served");
    }

    @Test
    void theFlushHappensOnTheCallingThreadWhenNoWorkerCouldBeStarted() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("no worker");
        };
        var flushThreads = new CopyOnWriteArrayList<Thread>();
        var exporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                flushThreads.add(Thread.currentThread());
            }
        };
        var failures = new CopyOnWriteArrayList<Throwable>();
        var scheduler = scheduler(rejecting, failures, exporter);

        scheduler.flush();

        assertEquals(1, flushThreads.size(), "the flush must still happen when no worker can be started");
        assertSame(Thread.currentThread(), flushThreads.get(0), "the invocation boundary flushes inline");
        assertFalse(failures.isEmpty(), "the rejected worker is reported");
    }

    /** Runs {@code action} on its own thread and reports whether it returned within the deadline. */
    private static boolean returnsWithin(Runnable action, long timeoutMillis) throws InterruptedException {
        var returned = new CountDownLatch(1);
        var thread = new Thread(
                () -> {
                    action.run();
                    returned.countDown();
                },
                "deadline-runner");
        thread.setDaemon(true);
        thread.start();
        return returned.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
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

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
