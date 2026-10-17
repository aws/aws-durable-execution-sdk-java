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
 * A {@code flush()} issued from the thread currently serving the export pump is refused and reported, not waited on.
 *
 * <p>That thread is the only one able to serve the request it would be making — flush requests are served by the pump,
 * between records — so waiting for it is a wait-for cycle one thread wide, and the invocation never returns. With a
 * single exporter the fan-out runs on the pump thread, so anything an exporter's {@code export()} does synchronously is
 * enough to reach it.
 */
class ExportSchedulerReentrantFlushTest {

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
            var thread = new Thread(command, "reentrant-flush-worker");
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
    void flushReenteredFromThePumpThreadIsRefusedReportedAndLeavesTheSchedulerUsable() throws Exception {
        var exporter = new CountingExporter();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var holder = new AtomicReference<ExportScheduler>();
        var reentrantFlushReturned = new CountDownLatch(1);
        var flushesSeenByTheRefusedCall = new AtomicInteger(-1);

        var scheduler = new ExportScheduler(
                List.of(exporter),
                (rec, exp) -> {
                    exp.export(rec);
                    if (reentrantFlushReturned.getCount() > 0 && "SUCCEEDED".equals(rec.status())) {
                        // Re-entering the scheduler from inside the fan-out: this is the pump's own thread.
                        holder.get().flush();
                        flushesSeenByTheRefusedCall.set(exporter.flushes.get());
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
                "reentrant-flush-invocation");
        invocation.setDaemon(true);
        invocation.start();

        assertTrue(
                reentrantFlushReturned.await(DEADLINE_MILLIS, MILLISECONDS),
                "flush() re-entered from the pump thread never returned: the only thread that can serve the request is"
                        + " the one waiting for it");
        assertTrue(
                drainReturned.await(DEADLINE_MILLIS, MILLISECONDS),
                "drain() never returned after the re-entrant flush");
        invocation.join(DEADLINE_MILLIS);

        // Reported, not silently swallowed, and nothing thrown into the caller.
        assertEquals(1, failures.size(), "exactly one failure reported: " + failures);
        assertTrue(
                failures.get(0) instanceof IllegalStateException,
                "the refusal is reported as an IllegalStateException: " + failures.get(0));
        assertTrue(
                failures.get(0).getMessage().contains("flush()"),
                "the report names the refused call: " + failures.get(0).getMessage());
        assertEquals(0, flushesSeenByTheRefusedCall.get(), "the refused request must not have reached an exporter");

        // Still usable: the next invocation's record is exported and its flush — from a thread that is not the pump —
        // is
        // served exactly as before.
        var secondExecution = Executions.plugin(scheduler, arn(1));
        scheduler.schedule(secondExecution, record(arn(1), "SUCCEEDED"));
        scheduler.drain(secondExecution);
        scheduler.flush();

        assertEquals(2, exporter.exports.get(), "both records reached the exporter");
        assertEquals(1, exporter.flushes.get(), "the next invocation's flush is served normally");
        assertEquals(1, failures.size(), "no further failure after the refusal: " + failures);
    }
}
