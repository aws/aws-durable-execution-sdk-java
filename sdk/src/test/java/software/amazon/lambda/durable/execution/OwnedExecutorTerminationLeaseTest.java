// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class OwnedExecutorTerminationLeaseTest {
    enum Path {
        GRACEFUL,
        SECOND_WAIT,
        FORCED
    }

    static Stream<Arguments> confirmedCases() {
        return Stream.of(Path.values())
                .flatMap(path -> Stream.of(false, true)
                        .flatMap(queryFatal ->
                                Stream.of(false, true).map(priorFatal -> Arguments.of(path, queryFatal, priorFatal))));
    }

    @ParameterizedTest
    @MethodSource("confirmedCases")
    void confirmedTerminationReleasesLeaseEvenWhenLaterStateQueryFails(
            Path path, boolean queryFatal, boolean priorFatal) throws Exception {
        var firstFatal = new InternalError("first handler fatal");
        Throwable queryFailure = queryFatal
                ? new InternalError("state query fatal")
                : new IllegalStateException("state query diagnostic");
        var shutdownFailure = new IllegalArgumentException("graceful shutdown failed before delegation");
        var queryEnabled = new AtomicBoolean(true);
        var confirmed = new AtomicBoolean();
        var awaits = new AtomicInteger();
        var forced = new AtomicInteger();
        var queriesAfterTermination = new AtomicInteger();
        var tailEntered = new CountDownLatch(1);
        var releaseTail = new CountDownLatch(path == Path.SECOND_WAIT ? 1 : 0);
        var workerFatal = new AtomicReference<Throwable>();
        var workerEscaped = new CountDownLatch(1);
        var pool =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), task -> {
                    var thread = new Thread(task, "termination-lease-owner");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerFatal.set(failure);
                        workerEscaped.countDown();
                    });
                    return thread;
                }) {
                    @Override
                    public void shutdown() {
                        if (path == Path.FORCED && queryEnabled.get()) throw shutdownFailure;
                        super.shutdown();
                    }

                    @Override
                    public List<Runnable> shutdownNow() {
                        forced.incrementAndGet();
                        return super.shutdownNow();
                    }

                    @Override
                    protected void afterExecute(Runnable task, Throwable failure) {
                        tailEntered.countDown();
                        try {
                            assertTrue(releaseTail.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                        if (path == Path.SECOND_WAIT && awaits.incrementAndGet() == 1) {
                            assertTrue(tailEntered.await(3, TimeUnit.SECONDS));
                            var stopped = super.awaitTermination(0, TimeUnit.NANOSECONDS);
                            assertFalse(stopped, "The actual worker tail is still held at the first wait");
                            releaseTail.countDown();
                            return stopped;
                        }
                        var stopped = super.awaitTermination(timeout, unit);
                        if (stopped) confirmed.set(true);
                        return stopped;
                    }

                    @Override
                    public boolean isShutdown() {
                        if (queryEnabled.get() && confirmed.get()) {
                            queriesAfterTermination.incrementAndGet();
                            ExceptionHelper.sneakyThrow(queryFailure);
                        }
                        return super.isShutdown();
                    }
                };
        assertEquals(1, pool.prestartAllCoreThreads());
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> pool)
                .build();
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                if (priorFatal) throw firstFatal;
                                return value;
                            },
                            config));
            if (priorFatal) assertSame(firstFatal, observed);
            else if (queryFatal || path != Path.FORCED) assertSame(queryFailure, observed);
            else
                assertSame(
                        shutdownFailure,
                        assertInstanceOf(UnrecoverableDurableExecutionException.class, observed)
                                .getCause());
            assertTrue(contains(observed, queryFailure), "The state-query diagnostic must remain visible");
            if (path == Path.FORCED) assertTrue(contains(observed, shutdownFailure));
            assertTrue(confirmed.get(), "Termination must have been confirmed by a real pool wait");
            assertTrue(pool.isTerminated());
            assertEquals(1, queriesAfterTermination.get(), "The later diagnostic query still runs");
            assertEquals(path == Path.GRACEFUL ? 0 : 1, forced.get());
            if (priorFatal) {
                assertTrue(workerEscaped.await(3, TimeUnit.SECONDS));
                assertSame(firstFatal, workerFatal.get());
            }

            queryEnabled.set(false);
            var rejected = assertThrows(
                    IllegalStateException.class,
                    () -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> fail("must not dispatch"),
                            config));
            assertEquals(
                    "Invocation executor factory returned a shutdown executor",
                    rejected.getMessage(),
                    "Released lease allows closed-state validation instead of the stale already-in-use rejection");
        } finally {
            queryEnabled.set(false);
            releaseTail.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unconfirmedOrActiveExecutorKeepsItsLease(boolean stillActive) throws Exception {
        var injected = new AtomicBoolean(true);
        var attempted = new AtomicBoolean();
        var confirms = new AtomicInteger();
        var queries = new AtomicInteger();
        var awaitFailure = new IllegalArgumentException("termination not observed");
        var queryFailure = new IllegalStateException("state unknown");
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void shutdown() {
                attempted.set(true);
                if (!injected.get() || !stillActive) super.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return injected.get() && stillActive ? List.of() : super.shutdownNow();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                if (injected.get() && !stillActive) throw awaitFailure;
                var stopped = super.awaitTermination(injected.get() ? 0 : timeout, unit);
                if (stopped) confirms.incrementAndGet();
                return stopped;
            }

            @Override
            public boolean isShutdown() {
                if (injected.get() && attempted.get()) {
                    queries.incrementAndGet();
                    throw queryFailure;
                }
                return super.isShutdown();
            }
        };
        pool.prestartAllCoreThreads();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> pool)
                .build();
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input(), null, TypeToken.get(String.class), (value, context) -> value, config));
            assertTrue(contains(observed, queryFailure));
            if (!stillActive) assertTrue(contains(observed, awaitFailure));
            assertEquals(0, confirms.get());
            if (stillActive) assertFalse(pool.isTerminated());
            var queryCount = queries.get();
            var rejected = assertThrows(
                    IllegalStateException.class,
                    () -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> fail("must not dispatch"),
                            config));
            assertEquals("Invocation executor factory returned an executor already in use", rejected.getMessage());
            assertEquals(queryCount, queries.get(), "An existing lease must be rejected before any state callback");
        } finally {
            injected.set(false);
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static boolean contains(Throwable root, Throwable target) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var next = pending.remove();
            if (next == target) return true;
            if (!seen.add(next)) continue;
            if (next.getCause() != null) pending.add(next.getCause());
            Collections.addAll(pending, next.getSuppressed());
        }
        return false;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/lease",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("lease")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }
}
