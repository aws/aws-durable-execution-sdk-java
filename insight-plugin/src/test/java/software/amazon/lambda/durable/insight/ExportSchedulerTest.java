// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExportSchedulerTest {

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class RejectOnceExecutor implements Executor {
        private Runnable task;
        private boolean reject = true;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                reject = false;
                throw new RejectedExecutionException("transient rejection");
            }
            task = command;
        }

        void run() {
            task.run();
        }
    }

    @Test
    void preservesNoDelayBurstWithinCapacityAndFlushesAfterFinal() {
        var executor = new ManualExecutor();
        var calls = new ArrayList<String>();
        var scheduler = scheduler(16, executor, calls, new AtomicInteger());

        for (var i = 1; i <= 11; i++) {
            assertTrue(scheduler.schedule(record("running-" + i)));
        }
        var drained = scheduler.sealAndDrain(record("final"));

        assertFalse(drained.isDone());
        executor.runNext();
        drained.join();

        assertEquals(13, calls.size());
        for (var i = 1; i <= 11; i++) {
            assertEquals("export:running-" + i, calls.get(i - 1));
        }
        assertEquals("export:final", calls.get(11));
        assertEquals("flush", calls.get(12));
    }

    @Test
    void retriesQueuedRecordsAfterTransientExecutorRejection() {
        var executor = new RejectOnceExecutor();
        var calls = new ArrayList<String>();
        var failures = new AtomicInteger();
        var scheduler = scheduler(4, executor, calls, failures);

        scheduler.schedule(record("running-1"));
        assertEquals(1, failures.get());
        scheduler.schedule(record("running-2"));
        var drained = scheduler.sealAndDrain(record("final"));
        executor.run();
        drained.join();

        assertEquals(List.of("export:running-1", "export:running-2", "export:final", "flush"), calls);
    }

    @Test
    void dropsOldestWaitingRecordsOnlyAfterCapacityIsReached() {
        var executor = new ManualExecutor();
        var calls = new ArrayList<String>();
        var scheduler = scheduler(3, executor, calls, new AtomicInteger());

        for (var i = 1; i <= 5; i++) {
            scheduler.schedule(record("running-" + i));
        }
        var drained = scheduler.sealAndDrain(record("final"));
        executor.runNext();
        drained.join();

        assertEquals(List.of("export:running-4", "export:running-5", "export:final", "flush"), calls);
    }

    @Test
    void rejectsRecordsScheduledAfterFinalBarrier() {
        var executor = new ManualExecutor();
        var calls = new ArrayList<String>();
        var scheduler = scheduler(3, executor, calls, new AtomicInteger());

        scheduler.schedule(record("running"));
        var drained = scheduler.sealAndDrain(record("final"));
        assertFalse(scheduler.schedule(record("late-running")));
        executor.runNext();
        drained.join();

        assertEquals(List.of("export:running", "export:final", "flush"), calls);
    }

    @Test
    void restartsWorkerWhenFinalRecordArrivesAfterQueueWentIdle() {
        var executor = new ManualExecutor();
        var calls = new ArrayList<String>();
        var scheduler = scheduler(3, executor, calls, new AtomicInteger());

        scheduler.schedule(record("running"));
        executor.runNext();
        assertEquals(List.of("export:running"), calls);

        var drained = scheduler.sealAndDrain(record("final"));
        assertFalse(drained.isDone());
        executor.runNext();
        drained.join();

        assertEquals(List.of("export:running", "export:final", "flush"), calls);
    }

    @Test
    void exporterFailureDoesNotSkipLaterRecordsOrFlush() {
        var executor = new ManualExecutor();
        var calls = new ArrayList<String>();
        var failures = new AtomicInteger();
        var scheduler = new ExportScheduler(
                3,
                executor,
                record -> {
                    calls.add("export:" + record.executionName);
                    if ("broken".equals(record.executionName)) {
                        throw new AssertionError("boom");
                    }
                },
                () -> calls.add("flush"),
                ignored -> failures.incrementAndGet());

        scheduler.schedule(record("broken"));
        var drained = scheduler.sealAndDrain(record("final"));
        executor.runNext();
        drained.join();

        assertEquals(List.of("export:broken", "export:final", "flush"), calls);
        assertEquals(1, failures.get());
    }

    private ExportScheduler scheduler(int capacity, Executor executor, List<String> calls, AtomicInteger failures) {
        return new ExportScheduler(
                capacity,
                executor,
                record -> calls.add("export:" + record.executionName),
                () -> calls.add("flush"),
                ignored -> failures.incrementAndGet());
    }

    private WorkflowInsightRecord record(String executionName) {
        var record = new WorkflowInsightRecord();
        record.executionName = executionName;
        return record;
    }
}
