// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(20)
class OwnedExecutorForcedShutdownTest {
    enum First {
        ORDINARY,
        FATAL,
        WRAPPED_FATAL
    }

    enum Later {
        NONE,
        FORCED_FATAL,
        STATE_FATAL,
        FORCED_ORDINARY_STATE_FATAL
    }

    static Stream<Arguments> cases() {
        var normal = Stream.of(First.values())
                .flatMap(first -> Stream.of(Later.values())
                        .flatMap(later ->
                                Stream.of(false, true).map(prior -> Arguments.of(first, later, prior, false))));
        return Stream.concat(
                normal,
                Stream.of(
                        Arguments.of(First.ORDINARY, Later.NONE, false, true),
                        Arguments.of(First.ORDINARY, Later.FORCED_FATAL, false, true)));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aThrowingGracefulShutdownStillStopsItsOwnedWorkers(
            First first, Later later, boolean handlerFatal, boolean acknowledged) throws Exception {
        var earlierHandlerFatal = new InternalError("earlier handler fatal");
        var shutdownFatal = new InternalError("graceful shutdown fatal");
        var ordinaryShutdown = new IllegalStateException("graceful shutdown failed before delegation");
        Throwable shutdownFailure = switch (first) {
            case ORDINARY -> ordinaryShutdown;
            case FATAL -> shutdownFatal;
            case WRAPPED_FATAL -> new CompletionException(shutdownFatal);
        };
        Throwable forcedFailure = later == Later.FORCED_FATAL
                ? new InternalError("forced stop fatal")
                : later == Later.FORCED_ORDINARY_STATE_FATAL
                        ? new IllegalArgumentException("forced stop diagnostic")
                        : null;
        Throwable stateFailure = later == Later.STATE_FATAL || later == Later.FORCED_ORDINARY_STATE_FATAL
                ? new InternalError("state query fatal")
                : null;
        Throwable expectedFatal = handlerFatal
                ? earlierHandlerFatal
                : first != First.ORDINARY ? shutdownFatal : later == Later.FORCED_FATAL ? forcedFailure : stateFailure;
        var end = new AtomicReference<InvocationEndInfo>();
        var ends = new AtomicInteger();
        var owner = new AtomicReference<Thread>();
        var endOwner = new AtomicReference<Thread>();
        var caller = new AtomicReference<Thread>();
        var callerInterrupted = new AtomicBoolean();
        var manager = new AtomicReference<ExecutionManager>();
        var endEntered = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(acknowledged ? 1 : 0);
        var workerFailure = new AtomicReference<Throwable>();
        var workerEscaped = new CountDownLatch(1);
        var users = new FailingPool(shutdownFailure, forcedFailure, stateFailure, workerFailure, workerEscaped);
        assertEquals(1, users.prestartAllCoreThreads());
        var callers = Executors.newSingleThreadExecutor();
        var client = TestUtils.createMockClient();
        var value = acknowledged ? "x".repeat(6 * 1024 * 1024 + 100) : "result";
        var config = DurableConfig.builder()
                .withInvocationExecutorFactory(() -> users)
                .withDurableExecutionClient(client)
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    public void onInvocationStart(InvocationInfo info) {
                        owner.set(Thread.currentThread());
                    }

                    public void onInvocationEnd(InvocationEndInfo info) {
                        endOwner.set(Thread.currentThread());
                        end.set(info);
                        ends.incrementAndGet();
                        endEntered.countDown();
                        try {
                            assertTrue(releaseEnd.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .build();
        try {
            var invocation = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (input, context) -> {
                                manager.set(((DurableContextImpl) context).getExecutionManager());
                                if (handlerFatal) throw earlierHandlerFatal;
                                return value;
                            },
                            config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            assertTrue(endEntered.await(5, TimeUnit.SECONDS));
            if (acknowledged) caller.get().interrupt();
            Throwable observed;
            if (acknowledged && expectedFatal == null) {
                var output = invocation.get(5, TimeUnit.SECONDS);
                assertEquals(ExecutionStatus.SUCCEEDED, output.status());
                assertEquals("", output.result());
                observed = manager.get().getLifecycleFailure();
            } else {
                observed = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                        .getCause();
                if (expectedFatal != null) assertSame(expectedFatal, observed);
                else {
                    var failure = assertInstanceOf(UnrecoverableDurableExecutionException.class, observed);
                    assertEquals("Invocation executor shutdown failed", failure.getMessage());
                    assertSame(ordinaryShutdown, failure.getCause());
                }
            }
            assertTrue(users.isTerminated(), "A graceful callback failure must not retain live owned workers");
            assertEquals(1, users.gracefulCalls.get());
            assertEquals(1, users.forcedCalls.get(), "Normal finalization forces cleanup once, without close retry");
            assertTrue(contains(observed, first == First.ORDINARY ? ordinaryShutdown : shutdownFatal));
            if (forcedFailure != null) assertTrue(contains(observed, forcedFailure));
            if (stateFailure != null) assertTrue(contains(observed, stateFailure));
            assertEquals(acknowledged, callerInterrupted.get());
            assertEquals(1, ends.get());
            assertSame(owner.get(), endOwner.get());
            assertEquals(
                    handlerFatal ? InvocationStatus.RETRYING : InvocationStatus.SUCCEEDED,
                    end.get().invocationStatus());
            assertSame(handlerFatal ? earlierHandlerFatal : null, end.get().executionError());
            if (handlerFatal) {
                assertTrue(workerEscaped.await(3, TimeUnit.SECONDS));
                assertSame(earlierHandlerFatal, workerFailure.get());
            } else assertNull(workerFailure.get());
            if (acknowledged)
                verify(client, times(1))
                        .checkpoint(
                                any(),
                                any(),
                                argThat(updates -> updates.size() == 1
                                        && updates.get(0).type() == OperationType.EXECUTION
                                        && updates.get(0).action() == OperationAction.SUCCEED));
            if (stateFailure == null) {
                users.inject.set(false);
                var rejected = assertThrows(
                        IllegalStateException.class,
                        () -> DurableExecutor.execute(
                                input(),
                                null,
                                TypeToken.get(String.class),
                                (input, context) -> "must not run",
                                config));
                assertEquals(
                        "Invocation executor factory returned a shutdown executor",
                        rejected.getMessage(),
                        "The stopped executor's normal lease is released");
            }
        } finally {
            releaseEnd.countDown();
            users.dispose();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void sharedExecutorsDoNotEnterOwnedShutdownRecovery() throws Exception {
        var users = new FailingPool(
                new IllegalStateException("must not shut down shared executor"),
                null,
                null,
                new AtomicReference<>(),
                new CountDownLatch(1));
        users.prestartAllCoreThreads();
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(users)
                    .withDurableExecutionClient(TestUtils.createMockClient())
                    .build();
            assertEquals(
                    ExecutionStatus.SUCCEEDED,
                    DurableExecutor.execute(
                                    input(), null, TypeToken.get(String.class), (value, context) -> value, config)
                            .status());
            assertEquals(0, users.gracefulCalls.get());
            assertEquals(0, users.forcedCalls.get());
            assertFalse(users.isShutdown());
        } finally {
            users.dispose();
        }
    }

    private static final class FailingPool extends ThreadPoolExecutor {
        private final Throwable first;
        private final Throwable forced;
        private final Throwable state;
        private final AtomicBoolean inject = new AtomicBoolean(true);
        private final AtomicBoolean firstCall = new AtomicBoolean(true);
        private final AtomicBoolean shutdownAttempted = new AtomicBoolean();
        private final AtomicInteger gracefulCalls = new AtomicInteger();
        private final AtomicInteger forcedCalls = new AtomicInteger();

        private FailingPool(
                Throwable first,
                Throwable forced,
                Throwable state,
                AtomicReference<Throwable> workerFailure,
                CountDownLatch escaped) {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), command -> {
                var thread = new Thread(command, "forced-shutdown-owner");
                thread.setUncaughtExceptionHandler((ignored, failure) -> {
                    workerFailure.set(failure);
                    escaped.countDown();
                });
                return thread;
            });
            this.first = first;
            this.forced = forced;
            this.state = state;
        }

        @Override
        public void shutdown() {
            if (inject.get()) {
                gracefulCalls.incrementAndGet();
                shutdownAttempted.set(true);
                if (firstCall.compareAndSet(true, false)) ExceptionHelper.sneakyThrow(first);
            }
            super.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            var result = super.shutdownNow();
            if (inject.get()) {
                forcedCalls.incrementAndGet();
                if (forced != null) ExceptionHelper.sneakyThrow(forced);
            }
            return result;
        }

        @Override
        public boolean isShutdown() {
            if (inject.get() && shutdownAttempted.get() && state != null) ExceptionHelper.sneakyThrow(state);
            return super.isShutdown();
        }

        private void dispose() throws InterruptedException {
            inject.set(false);
            super.shutdownNow();
            assertTrue(super.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static boolean contains(Throwable root, Throwable target) {
        if (root == null) return false;
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
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/forced/exec",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("exec")
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
