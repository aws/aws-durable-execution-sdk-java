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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class OwnedExecutorInitializationFailureTest {
    enum Kind {
        ORDINARY,
        FATAL,
        WRAPPED_FATAL
    }

    enum Cleanup {
        NONE,
        ORDINARY,
        FATAL
    }

    static Stream<Arguments> failures() {
        return Stream.of(Kind.values())
                .flatMap(kind -> Stream.of(Cleanup.values()).map(cleanup -> Arguments.of(kind, cleanup)));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void failedStateValidationDisposesOnlyTheClaimedPool(Kind kind, Cleanup cleanup) throws Exception {
        var initialFatal = new InternalError("initial state query fatal");
        Throwable initial = switch (kind) {
            case ORDINARY -> new IllegalArgumentException("initial state query failed");
            case FATAL -> initialFatal;
            case WRAPPED_FATAL -> new CompletionException(initialFatal);
        };
        Throwable cleanupFailure = switch (cleanup) {
            case NONE -> null;
            case ORDINARY -> new IllegalStateException("graceful shutdown failed");
            case FATAL -> new InternalError("later shutdown fatal");
        };
        var expected = kind != Kind.ORDINARY ? initialFatal : cleanup == Cleanup.FATAL ? cleanupFailure : initial;
        var bodies = new AtomicInteger();
        var starts = new AtomicInteger();
        var ends = new AtomicInteger();
        var pool = new QueryFailingPool(initial, cleanupFailure);
        assertEquals(1, pool.prestartAllCoreThreads());
        var config = config(pool, starts, ends);
        try {
            var observed = assertThrows(Throwable.class, () -> invoke(config, bodies));
            assertSame(expected, observed, "Preserve the original failure unless cleanup introduces the first fatal");
            assertTrue(pool.isTerminated(), "Prestarted workers must be disposed before failed setup returns");
            assertTrue(pool.shutdownCalls.get() > 0 || pool.forcedCalls.get() > 0);
            assertEquals(1, pool.queryCalls.get(), "Cleanup does not need to repeat the failed state query");
            assertEquals(0, bodies.get());
            assertEquals(0, starts.get());
            assertEquals(0, ends.get());
            if (cleanupFailure != null) {
                assertTrue(contains(observed, cleanupFailure), "Retain later cleanup diagnostics");
                assertTrue(contains(observed, kind == Kind.ORDINARY ? initial : initialFatal));
                assertTrue(pool.forcedCalls.get() > 0, "Failed graceful shutdown still gets a best-effort forced stop");
            }
            pool.inject.set(false);
            var rejected = assertThrows(IllegalStateException.class, () -> invoke(config, bodies));
            assertEquals(
                    "Invocation executor factory returned a shutdown executor",
                    rejected.getMessage(),
                    "Failed initialization must release its lease, so closed-state validation remains authoritative");
            assertEquals(0, bodies.get());
        } finally {
            pool.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationCleanupPreservesCallerInterrupt(boolean interrupted) throws Exception {
        var initial = new IllegalArgumentException("initial query failed");
        var pool = new QueryFailingPool(initial, null);
        pool.prestartAllCoreThreads();
        try {
            if (interrupted) Thread.currentThread().interrupt();
            var observed = assertThrows(
                    Throwable.class,
                    () -> invoke(config(pool, new AtomicInteger(), new AtomicInteger()), new AtomicInteger()));
            assertSame(initial, observed);
            assertEquals(interrupted, Thread.interrupted());
            assertTrue(pool.isTerminated());
        } finally {
            Thread.interrupted();
            pool.dispose();
        }
    }

    @Test
    void anAlreadyLeasedPoolIsNeitherQueriedNorStopped() throws Exception {
        var pool = new QueryFailingPool(new IllegalArgumentException("foreign state query must not run"), null);
        pool.inject.set(false);
        pool.prestartAllCoreThreads();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callers = Executors.newSingleThreadExecutor();
        var config = config(pool, new AtomicInteger(), new AtomicInteger());
        try {
            var first = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                        return value;
                    },
                    config));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queriesBeforeSecond = pool.queryCalls.get();
            pool.inject.set(true);
            var secondBodies = new AtomicInteger();
            var rejected = assertThrows(IllegalStateException.class, () -> invoke(config, secondBodies));
            assertEquals("Invocation executor factory returned an executor already in use", rejected.getMessage());
            assertEquals(queriesBeforeSecond, pool.queryCalls.get(), "Do not call foreign executor state callbacks");
            assertEquals(0, pool.shutdownCalls.get());
            assertEquals(0, pool.forcedCalls.get());
            assertEquals(0, secondBodies.get());
            assertFalse(first.isDone());
            pool.inject.set(false);
            release.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, first.get(5, TimeUnit.SECONDS).status());
        } finally {
            pool.inject.set(false);
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            pool.dispose();
        }
    }

    private static DurableConfig config(QueryFailingPool pool, AtomicInteger starts, AtomicInteger ends) {
        return DurableConfig.builder()
                .withInvocationExecutorFactory(() -> pool)
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    public void onInvocationStart(InvocationInfo info) {
                        starts.incrementAndGet();
                    }

                    public void onInvocationEnd(InvocationEndInfo info) {
                        ends.incrementAndGet();
                    }
                })
                .build();
    }

    private static Object invoke(DurableConfig config, AtomicInteger bodies) {
        return DurableExecutor.execute(
                input(),
                null,
                TypeToken.get(String.class),
                (value, context) -> {
                    bodies.incrementAndGet();
                    return value;
                },
                config);
    }

    private static final class QueryFailingPool extends ThreadPoolExecutor {
        private final Throwable initial;
        private final Throwable cleanup;
        private final AtomicBoolean inject = new AtomicBoolean(true);
        private final AtomicInteger queryCalls = new AtomicInteger();
        private final AtomicInteger shutdownCalls = new AtomicInteger();
        private final AtomicInteger forcedCalls = new AtomicInteger();

        private QueryFailingPool(Throwable initial, Throwable cleanup) {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            this.initial = initial;
            this.cleanup = cleanup;
        }

        @Override
        public boolean isShutdown() {
            queryCalls.incrementAndGet();
            if (inject.get()) ExceptionHelper.sneakyThrow(initial);
            return super.isShutdown();
        }

        @Override
        public void shutdown() {
            shutdownCalls.incrementAndGet();
            if (inject.get() && cleanup != null) ExceptionHelper.sneakyThrow(cleanup);
            super.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            forcedCalls.incrementAndGet();
            return super.shutdownNow();
        }

        private void dispose() throws InterruptedException {
            inject.set(false);
            super.shutdownNow();
            assertTrue(super.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static boolean contains(Throwable root, Throwable target) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var next = pending.removeFirst();
            if (next == target) return true;
            if (!seen.add(next)) continue;
            if (next.getCause() != null) pending.add(next.getCause());
            Collections.addAll(pending, next.getSuppressed());
        }
        return false;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/init",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("init")
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
