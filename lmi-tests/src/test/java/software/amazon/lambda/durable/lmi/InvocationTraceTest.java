// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class InvocationTraceTest {
    @Test
    void crossRequestBoundariesCannotMutateCountersAheadOfTheirEvents() throws Exception {
        var executor = Executors.newFixedThreadPool(3);
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var peer = TraceCapture.trace("peer");
        var probe = TraceCapture.trace("probe");
        var exitingThread = new AtomicReference<Thread>();
        var enteringThread = new AtomicReference<Thread>();
        try (var capture = new TraceCapture()) {
            peer.taskEnter("peer");
            try {
                var writer = executor.submit(() -> peer.event("PAUSE", blockingDetails(writing, release)));
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                var exiting = executor.submit(() -> {
                    exitingThread.set(Thread.currentThread());
                    peer.taskExit("peer");
                });
                awaitBlocked(exitingThread);
                var entering = executor.submit(() -> {
                    enteringThread.set(Thread.currentThread());
                    probe.taskEnter("probe");
                });
                assertEquals(1, peer.tasks.get(), "An exit must not mutate its counter before it can record its event");
                awaitBlocked(enteringThread);
                assertEquals(0, probe.tasks.get(), "Another request must share the same boundary lock");
                release.countDown();
                writer.get(5, TimeUnit.SECONDS);
                exiting.get(5, TimeUnit.SECONDS);
                entering.get(5, TimeUnit.SECONDS);
                probe.taskExit("probe");
                var live = 0;
                for (var event : capture.events()) {
                    if ("TASK_ENTER".equals(event.get("kind"))) live++;
                    if ("TASK_EXIT".equals(event.get("kind"))) live--;
                    assertEquals(live, ((Number) event.get("liveTasks")).intValue());
                }
            } finally {
                release.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                if (peer.tasks.get() > 0) peer.taskExit("peer");
                if (probe.tasks.get() > 0) probe.taskExit("probe");
            }
        }
    }

    private static Map<String, Object> blockingDetails(CountDownLatch writing, CountDownLatch release) {
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                writing.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return Set.of();
            }
        };
    }

    private static void awaitBlocked(AtomicReference<Thread> reference) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (reference.get() == null || reference.get().getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline, "Trace writer did not wait for the boundary lock");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
