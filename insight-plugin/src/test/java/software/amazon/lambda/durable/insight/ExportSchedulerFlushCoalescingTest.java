// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the flush cadence: at most one flush per invocation end that asks for one, invocation ends that
 * overlap may share a flush, and a request made while a flush is already running is never satisfied by that flush.
 *
 * <p>Coalescing is sound because every requester drains its own record before asking, so a flush that <em>starts</em>
 * after the request was made has that record in the buffer. It is what stops N ends that ask together from paying for N
 * serialized flush fan-outs — a 60&nbsp;ms exporter flush cost the slowest of 8 ends ~520&nbsp;ms before this change.
 */
class ExportSchedulerFlushCoalescingTest {

    /** A slow flush must not cost the caller more than a small multiple of the one flush it asked for. */
    private static final int PROMPTNESS_FACTOR = 3;

    private static String arn(int index) {
        return "arn:aws:lambda:us-west-2:111122223333:function:f:$LATEST/durable-execution/exec-" + index + "/inv-1";
    }

    private static WorkflowInsightRecord record(String executionArn, String status) {
        var r = new WorkflowInsightRecord();
        r.executionArn = executionArn;
        r.status = status;
        return r;
    }

    private static ExportScheduler scheduler(Executor executor, InsightExporter... exporters) {
        return new ExportScheduler(List.of(exporters), (rec, exp) -> exp.export(rec), t -> {}, executor);
    }

    /** Unbounded thread-per-task executor, like the cached pool the plugin injects in production. */
    private static Executor sharedWorkers() {
        return command -> {
            var thread = new Thread(command, "coalescing-worker");
            thread.setDaemon(true);
            thread.start();
        };
    }

    private static final class SlowFlushExporter implements InsightExporter {
        private final long flushMillis;
        final AtomicInteger flushes = new AtomicInteger();

        SlowFlushExporter(long flushMillis) {
            this.flushMillis = flushMillis;
        }

        @Override
        public void export(WorkflowInsightRecord record) {}

        @Override
        public void flush() {
            flushes.incrementAndGet();
            sleep(flushMillis);
        }
    }

    /**
     * N invocation ends whose records have already been delivered ask for their flush together: they share one flush,
     * so the slowest pays a small multiple of one flush rather than N times one.
     *
     * <p>The records are drained before the requests are made on purpose: this is coalescing on its own, with every
     * request already queued when the pump reaches its flush step. The harder case — ends that are still inside
     * {@code drain()} when the first request is served, and so cannot have asked yet — is
     * {@link #simultaneousDrainAndFlushEndsShareAFlushRatherThanOneEach()}.
     */
    @Test
    void invocationEndsAskingForAFlushTogetherShareOneFlush() {
        long flushMillis = 60;
        int executions = 8;
        var exporter = new SlowFlushExporter(flushMillis);
        var scheduler = scheduler(sharedWorkers(), exporter);

        var durations = Collections.synchronizedList(new java.util.ArrayList<Long>());
        var recordsDelivered = new CyclicBarrier(executions);
        var done = new CountDownLatch(executions);
        for (int i = 0; i < executions; i++) {
            String executionArn = arn(i);
            InsightPlugin execution = Executions.plugin(scheduler, executionArn);
            start("end-" + i, () -> {
                scheduler.schedule(execution, record(executionArn, "SUCCEEDED"));
                scheduler.drain(execution);
                awaitBarrier(recordsDelivered);
                long began = System.nanoTime();
                scheduler.flush();
                durations.add((System.nanoTime() - began) / 1_000_000L);
                done.countDown();
            });
        }
        assertTrue(await(done, 60_000), "an invocation end never returned");

        long slowest = Collections.max(durations);
        int flushes = exporter.flushes.get();
        System.out.printf(
                "COALESCING: %d invocation ends flushing together, %d ms exporter flush | slowest end returned after"
                        + " %d ms | flushes run: %d (one per end, %d, before coalescing)%n",
                executions, flushMillis, slowest, flushes, executions);

        assertTrue(flushes >= 1, "every invocation end must be covered by a flush");
        assertTrue(flushes <= executions, "at most one flush per invocation end: " + flushes + " for " + executions);
        assertTrue(
                slowest <= flushMillis * PROMPTNESS_FACTOR,
                "the slowest invocation end waited " + slowest + " ms for a " + flushMillis + " ms flush: ends that ask"
                        + " together must share a flush rather than serialize one fan-out each");
    }

    /**
     * The realistic shape: schedule, drain, flush, all landing at once. An end cannot ask for its flush until its own
     * record has been exported, so the pump exports the records a drain is waiting for before it spends a flush
     * fan-out; without that the ends are staggered one record per flush and each pays for a flush of its own.
     */
    @Test
    void simultaneousDrainAndFlushEndsShareAFlushRatherThanOneEach() {
        long flushMillis = 40;
        int executions = 8;
        var exporter = new SlowFlushExporter(flushMillis);
        var scheduler = scheduler(sharedWorkers(), exporter);

        var durations = Collections.synchronizedList(new java.util.ArrayList<Long>());
        var barrier = new CyclicBarrier(executions);
        var done = new CountDownLatch(executions);
        for (int i = 0; i < executions; i++) {
            String executionArn = arn(i);
            InsightPlugin execution = Executions.plugin(scheduler, executionArn);
            start("drain-and-flush-" + i, () -> {
                awaitBarrier(barrier);
                long began = System.nanoTime();
                scheduler.schedule(execution, record(executionArn, "SUCCEEDED"));
                scheduler.drain(execution);
                scheduler.flush();
                durations.add((System.nanoTime() - began) / 1_000_000L);
                done.countDown();
            });
        }
        assertTrue(await(done, 60_000), "an invocation end never returned");

        int flushes = exporter.flushes.get();
        long slowest = Collections.max(durations);
        System.out.printf(
                "COALESCING (drain then flush): %d simultaneous ends, %d ms exporter flush | slowest end after %d ms |"
                        + " flushes run: %d (one per end, %d, before coalescing)%n",
                executions, flushMillis, slowest, flushes, executions);
        assertTrue(flushes >= 1, "every invocation end must be covered by a flush");
        assertTrue(flushes <= executions, "at most one flush per invocation end: " + flushes + " for " + executions);
        assertTrue(
                slowest <= flushMillis * (PROMPTNESS_FACTOR + 1),
                "the slowest of " + executions + " simultaneous ends waited " + slowest + " ms for a " + flushMillis
                        + " ms flush: an end must not pay for one flush per end");
    }

    /**
     * The exact counts, deterministically: five requests made while a flush is running are not satisfied by it — that
     * flush cannot have seen their records — and they share the single flush that follows it.
     */
    @Test
    void requestsMadeWhileAFlushRunsShareTheNextFlushAndAreNeverSatisfiedByTheRunningOne() {
        var insideFirstFlush = new CountDownLatch(1);
        var releaseFirstFlush = new CountDownLatch(1);
        var flushStarts = new AtomicInteger();
        var flushCompletions = new AtomicInteger();
        var exporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                if (flushStarts.incrementAndGet() == 1) {
                    insideFirstFlush.countDown();
                    await(releaseFirstFlush, 30_000);
                }
                flushCompletions.incrementAndGet();
            }
        };
        var scheduler = scheduler(sharedWorkers(), exporter);

        start("first-flusher", scheduler::flush);
        assertTrue(await(insideFirstFlush, 5_000), "the pump never entered the first flush");

        int latecomers = 5;
        var returned = new CountDownLatch(latecomers);
        var startsSeenOnReturn = new CopyOnWriteArrayList<Integer>();
        for (int i = 0; i < latecomers; i++) {
            start("latecomer-" + i, () -> {
                scheduler.flush();
                startsSeenOnReturn.add(flushStarts.get());
                returned.countDown();
            });
        }
        sleep(300); // every latecomer is queued while the first flush is still inside the exporter

        assertFalse(
                await(returned, 200),
                "a request made while a flush was already running was satisfied by that flush, which cannot have seen"
                        + " the requester's record");
        releaseFirstFlush.countDown();
        assertTrue(await(returned, 10_000), "a queued request was never served");

        assertEquals(
                2,
                flushStarts.get(),
                "the five latecomers must share exactly one flush, taken as a batch after the first one ended");
        assertTrue(
                startsSeenOnReturn.stream().allMatch(starts -> starts >= 2),
                "each latecomer must be served by a flush that started after it was enqueued: " + startsSeenOnReturn);
        assertEquals(2, flushCompletions.get(), "no flush ran twice for the same batch");
    }

    /**
     * The same property under load, measured per request: when a request returns, a flush that started after the
     * request was made must already have completed. Flushes are serialized by the pump, so counting completions is
     * enough — a request satisfied by a flush that was already running would return with the completion count still at
     * or below the value observed before it asked.
     */
    @Test
    void everyRequestIsSatisfiedByAFlushThatStartedAfterItWasMade() {
        var flushStarts = new AtomicInteger();
        var flushCompletions = new AtomicInteger();
        var exporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                flushStarts.incrementAndGet();
                sleep(1);
                flushCompletions.incrementAndGet();
            }
        };
        var scheduler = scheduler(sharedWorkers(), exporter);

        int requests = 120;
        var done = new CountDownLatch(requests);
        var violations = new CopyOnWriteArrayList<String>();
        for (int i = 0; i < requests; i++) {
            String executionArn = arn(i);
            InsightPlugin execution = Executions.plugin(scheduler, executionArn);
            start("load-flusher-" + i, () -> {
                scheduler.schedule(execution, record(executionArn, "SUCCEEDED"));
                scheduler.drain(execution);
                int startsBefore = flushStarts.get();
                scheduler.flush();
                if (flushCompletions.get() <= startsBefore) {
                    violations.add("returned with completions=" + flushCompletions.get() + " after observing starts="
                            + startsBefore);
                }
                done.countDown();
            });
        }
        assertTrue(await(done, 60_000), "a request was never served");
        scheduler.drainAll();

        System.out.printf("COALESCING under load: %d requests satisfied by %d flushes%n", requests, flushStarts.get());
        assertEquals(List.of(), violations, "a request was credited to a flush that was already running");
        assertTrue(flushStarts.get() >= 1);
        assertTrue(
                flushStarts.get() <= requests,
                "at most one flush per request: " + flushStarts.get() + " for " + requests);
    }

    private static Thread start(String name, Runnable body) {
        var thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static boolean await(CountDownLatch latch, long timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(60, TimeUnit.SECONDS);
        } catch (Exception e) {
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
