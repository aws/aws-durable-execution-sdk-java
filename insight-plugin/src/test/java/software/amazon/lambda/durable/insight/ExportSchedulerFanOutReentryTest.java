// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A {@code flush()} or {@code drain()} issued from an exporter fan-out worker is refused and reported, not waited on.
 *
 * <p>With two or more exporters configured, the pump does not run the exporter callbacks itself: it submits one task
 * per exporter and then waits for all of them. A callback therefore runs on a worker the pump is blocked on, and a wait
 * for the pump issued from that worker is a wait-for cycle two threads wide — the worker parks on a future only the
 * pump can complete, and the pump cannot resume its loop until that worker returns. The single-exporter case runs the
 * callback on the pump thread itself and is covered by {@link ExportSchedulerReentrantFlushTest}; this covers the
 * fan-out, which the pump-thread identity check alone does not recognize.
 */
class ExportSchedulerFanOutReentryTest {

    /** Longest a call that must return promptly may take before the property under test is considered broken. */
    private static final long DEADLINE_MILLIS = 5_000;

    private static String arn(int index) {
        return "arn:aws:lambda:us-west-2:111122223333:function:f:$LATEST/durable-execution/exec-" + index + "/inv-1";
    }

    private static WorkflowInsightRecord record(String executionArn, String status) {
        var r = new WorkflowInsightRecord();
        r.executionArn = executionArn;
        r.status = status;
        return r;
    }

    /** Unbounded thread-per-task executor, like the cached pool the plugin injects in production. */
    private static Executor sharedWorkers() {
        return command -> {
            var thread = new Thread(command, "fan-out-reentry-worker");
            thread.setDaemon(true);
            thread.start();
        };
    }

    private static final class CountingExporter implements InsightExporter {
        final AtomicInteger exports = new AtomicInteger();
        final AtomicInteger flushes = new AtomicInteger();

        @Override
        public void export(WorkflowInsightRecord record) {
            exports.incrementAndGet();
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }
    }

    @Test
    void flushReenteredFromAFanOutWorkerIsRefusedReportedAndLosesNoWork() throws Exception {
        var first = new CountingExporter();
        var second = new CountingExporter();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var holder = new AtomicReference<ExportScheduler>();
        var reentrantFlushReturned = new CountDownLatch(1);
        var flushesSeenByTheRefusedCall = new AtomicInteger(-1);
        var reentered = new AtomicInteger();

        var scheduler = new ExportScheduler(
                List.of(first, second),
                (rec, exp) -> {
                    exp.export(rec);
                    // Only the first exporter re-enters, and only once, so exactly one refusal is expected.
                    if (exp == first && "SUCCEEDED".equals(rec.status()) && reentered.getAndIncrement() == 0) {
                        holder.get().flush();
                        flushesSeenByTheRefusedCall.set(first.flushes.get() + second.flushes.get());
                        reentrantFlushReturned.countDown();
                    }
                },
                failures::add,
                sharedWorkers());
        holder.set(scheduler);

        var drainReturned = new CountDownLatch(1);
        var firstExecution = Executions.plugin(scheduler, arn(0));
        var invocation = new Thread(
                () -> {
                    scheduler.schedule(firstExecution, record(arn(0), "SUCCEEDED"));
                    scheduler.drain(firstExecution);
                    drainReturned.countDown();
                },
                "fan-out-reentry-invocation");
        invocation.setDaemon(true);
        invocation.start();

        assertTrue(
                reentrantFlushReturned.await(DEADLINE_MILLIS, MILLISECONDS),
                "flush() re-entered from an exporter fan-out worker never returned: the pump is waiting for that"
                        + " worker, so nothing can serve the request it made");
        assertTrue(
                drainReturned.await(DEADLINE_MILLIS, MILLISECONDS),
                "drain() never returned after the re-entrant flush");
        invocation.join(DEADLINE_MILLIS);

        assertEquals(1, failures.size(), "exactly one failure reported: " + failures);
        assertTrue(
                failures.get(0) instanceof IllegalStateException,
                "the refusal is reported as an IllegalStateException: " + failures.get(0));
        assertTrue(
                failures.get(0).getMessage().contains("flush()"),
                "the report names the refused call: " + failures.get(0).getMessage());
        assertEquals(0, flushesSeenByTheRefusedCall.get(), "the refused request must not have reached an exporter");

        // No work lost: the record that was in flight reached both exporters.
        assertEquals(1, first.exports.get(), "the record reached the first exporter");
        assertEquals(1, second.exports.get(), "the record reached the second exporter");

        // Still usable from a thread that is not pump-dependent.
        var secondExecution = Executions.plugin(scheduler, arn(1));
        scheduler.schedule(secondExecution, record(arn(1), "SUCCEEDED"));
        scheduler.drain(secondExecution);
        scheduler.flush();

        assertEquals(2, first.exports.get(), "both records reached the first exporter");
        assertEquals(2, second.exports.get(), "both records reached the second exporter");
        assertEquals(1, first.flushes.get(), "the later flush is served normally on the first exporter");
        assertEquals(1, second.flushes.get(), "the later flush is served normally on the second exporter");
        assertEquals(1, failures.size(), "no further failure after the refusal: " + failures);
    }

    @Test
    void drainReenteredFromAFanOutWorkerIsRefusedReportedAndLosesNoWork() throws Exception {
        var first = new CountingExporter();
        var second = new CountingExporter();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var holder = new AtomicReference<ExportScheduler>();
        var pluginHolder = new AtomicReference<InsightPlugin>();
        var reentrantDrainReturned = new CountDownLatch(1);
        var reentered = new AtomicInteger();

        var scheduler = new ExportScheduler(
                List.of(first, second),
                (rec, exp) -> {
                    exp.export(rec);
                    if (exp == first && "SUCCEEDED".equals(rec.status()) && reentered.getAndIncrement() == 0) {
                        holder.get().drain(pluginHolder.get());
                        reentrantDrainReturned.countDown();
                    }
                },
                failures::add,
                sharedWorkers());
        holder.set(scheduler);

        var execution = Executions.plugin(scheduler, arn(0));
        pluginHolder.set(execution);

        var drainReturned = new CountDownLatch(1);
        var invocation = new Thread(
                () -> {
                    scheduler.schedule(execution, record(arn(0), "SUCCEEDED"));
                    scheduler.drain(execution);
                    drainReturned.countDown();
                },
                "fan-out-reentry-drain-invocation");
        invocation.setDaemon(true);
        invocation.start();

        assertTrue(
                reentrantDrainReturned.await(DEADLINE_MILLIS, MILLISECONDS),
                "drain() re-entered from an exporter fan-out worker never returned: the pump is waiting for that"
                        + " worker, so nothing can settle the signal it waited for");
        assertTrue(
                drainReturned.await(DEADLINE_MILLIS, MILLISECONDS),
                "the invocation's own drain() never returned after the re-entrant drain");
        invocation.join(DEADLINE_MILLIS);

        assertEquals(1, failures.size(), "exactly one failure reported: " + failures);
        assertTrue(
                failures.get(0) instanceof IllegalStateException,
                "the refusal is reported as an IllegalStateException: " + failures.get(0));
        assertTrue(
                failures.get(0).getMessage().contains("drain"),
                "the report names the refused call: " + failures.get(0).getMessage());

        // No work lost by refusing the drain: the record still reached every exporter, and the invocation's own drain
        // returned only once it had.
        assertEquals(1, first.exports.get(), "the record reached the first exporter");
        assertEquals(1, second.exports.get(), "the record reached the second exporter");
        assertTrue(!Executions.outstanding(execution), "the scheduler owes the invocation nothing after its drain");
    }
}
