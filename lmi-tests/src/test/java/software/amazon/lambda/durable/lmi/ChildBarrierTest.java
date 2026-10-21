// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class ChildBarrierTest {
    @Test
    void fourWorkersEstablishBothChildrenBeforeCoordinatorsQueue() throws Exception {
        var executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
        var rootsEntered = new CountDownLatch(2);
        var nestedQueued = new CountDownLatch(2);
        var barrier = new CyclicBarrier(2);
        var roots = new ArrayList<Future<String>>();
        try (var capture = new TraceCapture()) {
            try {
                for (var request : List.of("first", "second")) {
                    roots.add(executor.submit(() -> {
                        rootsEntered.countDown();
                        assertTrue(rootsEntered.await(5, TimeUnit.SECONDS));
                        return executor.submit(() -> {
                                    LifecycleHandler.awaitChildBarrier(barrier, TraceCapture.trace(request), 5000);
                                    var coordinator = executor.submit(() -> "done");
                                    nestedQueued.countDown();
                                    return coordinator.get(5, TimeUnit.SECONDS);
                                })
                                .get(5, TimeUnit.SECONDS);
                    }));
                }
                assertTrue(nestedQueued.await(5, TimeUnit.SECONDS));
                assertEquals(2, executor.getQueue().size(), "Both coordinators, not the second child, must be queued");
                assertTrue(roots.stream().noneMatch(Future::isDone));
                assertEquals(
                        2,
                        capture.events().stream()
                                .filter(e -> "CHILD_BARRIER_PASSED".equals(e.get("kind")))
                                .count());
                // Test-only release of the reproduced starvation, after asserting the exact topology.
                executor.setMaximumPoolSize(6);
                executor.setCorePoolSize(6);
                for (var root : roots) {
                    assertEquals("done", root.get(5, TimeUnit.SECONDS));
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void delayedSecondChildPreventsFirstChildFromSchedulingNestedWork() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        var scheduled = new AtomicBoolean();
        try (var capture = new TraceCapture()) {
            try {
                var first = executor.submit(() -> {
                    LifecycleHandler.awaitChildBarrier(barrier, TraceCapture.trace("first"), 5000);
                    scheduled.set(true);
                });
                waitForFirstChild(barrier);
                assertFalse(scheduled.get(), "The first child must not submit a coordinator before its peer enters");
                var second = executor.submit(
                        () -> LifecycleHandler.awaitChildBarrier(barrier, TraceCapture.trace("second"), 5000));
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertTrue(scheduled.get());
                var kinds = capture.events().stream().map(e -> e.get("kind")).toList();
                assertEquals(
                        List.of(
                                "CHILD_BARRIER_ENTER",
                                "CHILD_BARRIER_ENTER",
                                "CHILD_BARRIER_PASSED",
                                "CHILD_BARRIER_PASSED"),
                        kinds);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void timeoutBreaksTheCohortAndCannotBeReleasedByALateChild() {
        var barrier = new CyclicBarrier(2);
        try (var capture = new TraceCapture()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> LifecycleHandler.awaitChildBarrier(barrier, TraceCapture.trace("first"), 1));
            assertTrue(barrier.isBroken());
            assertThrows(
                    IllegalStateException.class,
                    () -> LifecycleHandler.awaitChildBarrier(barrier, TraceCapture.trace("late"), 5000));
            assertEquals(0, barrier.getNumberWaiting());
            assertEquals(
                    2,
                    capture.events().stream()
                            .filter(e -> "CHILD_BARRIER_FAILED".equals(e.get("kind")))
                            .count());
            assertFalse(capture.events().stream().anyMatch(e -> "CHILD_BARRIER_PASSED".equals(e.get("kind"))));
        }
    }

    @Test
    void interruptionPreservesTheFlagAndBreaksTheBarrier() {
        var barrier = new CyclicBarrier(2);
        try (var capture = new TraceCapture()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> LifecycleHandler.awaitChildBarrier(barrier, TraceCapture.trace("first"), 5000));
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(barrier.isBroken());
                assertEquals("CHILD_BARRIER_FAILED", capture.events().get(1).get("kind"));
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static void waitForFirstChild(CyclicBarrier barrier) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (barrier.getNumberWaiting() != 1 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(1, barrier.getNumberWaiting(), "First child did not reach the barrier");
    }
}
