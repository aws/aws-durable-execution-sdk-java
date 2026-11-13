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
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class OwnedExecutorFailureOrderingTest {
    enum Stage {
        SHUTDOWN,
        AWAIT
    }

    enum Failures {
        FATAL_THEN_ORDINARY,
        DEATH_THEN_FATAL,
        ORDINARY_THEN_FATAL,
        COMPLETION_FATAL,
        EXECUTION_FATAL
    }

    static Stream<Arguments> failureOrders() {
        return Stream.of(Stage.values())
                .flatMap(stage -> Stream.of(Failures.values())
                        .flatMap(order -> Stream.of(false, true).map(prior -> Arguments.of(stage, order, prior))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("failureOrders")
    void stateQueryCannotReplaceEarlierFailures(Stage stage, Failures order, boolean priorHandlerFatal)
            throws Exception {
        var handlerFatal = new InternalError("earlier handler fatal");
        Error shutdownFatal =
                order == Failures.DEATH_THEN_FATAL ? new ThreadDeath() : new InternalError("shutdown fatal");
        var ordinaryShutdown = new IllegalStateException("shutdown failed");
        Throwable shutdownFailure = switch (order) {
            case ORDINARY_THEN_FATAL -> ordinaryShutdown;
            case COMPLETION_FATAL -> new CompletionException(shutdownFatal);
            case EXECUTION_FATAL -> new ExecutionException(shutdownFatal);
            default -> shutdownFatal;
        };
        Throwable queryFailure = order == Failures.DEATH_THEN_FATAL || order == Failures.ORDINARY_THEN_FATAL
                ? new InternalError("later state-query fatal")
                : new IllegalArgumentException("later state-query failure");
        var expected =
                priorHandlerFatal ? handlerFatal : order == Failures.ORDINARY_THEN_FATAL ? queryFailure : shutdownFatal;
        var inject = new AtomicBoolean(true);
        var shutdownStarted = new AtomicBoolean();
        var bodyExited = new AtomicBoolean();
        var endBeforeShutdown = new AtomicBoolean();
        var shutdownCalls = new AtomicInteger();
        var stateCalls = new AtomicInteger();
        var events = new CopyOnWriteArrayList<String>();
        var owner = new AtomicReference<Thread>();
        var endThread = new AtomicReference<Thread>();
        var end = new AtomicReference<InvocationEndInfo>();
        var ends = new AtomicInteger();
        var workerFailure = new AtomicReference<Throwable>();
        var workerEscaped = new CountDownLatch(1);
        var users =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), action -> {
                    var thread = new Thread(action, "ordered-shutdown-owner");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerFailure.set(failure);
                        workerEscaped.countDown();
                    });
                    return thread;
                }) {
                    @Override
                    public void shutdown() {
                        if (inject.get()) {
                            events.add("shutdown");
                            shutdownCalls.incrementAndGet();
                            endBeforeShutdown.set(bodyExited.get() && ends.get() == 1);
                        }
                        shutdownStarted.set(true);
                        super.shutdown();
                        if (inject.get() && stage == Stage.SHUTDOWN) ExceptionHelper.sneakyThrow(shutdownFailure);
                    }

                    @Override
                    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                        if (inject.get() && stage == Stage.AWAIT) {
                            events.add("await");
                            ExceptionHelper.sneakyThrow(shutdownFailure);
                        }
                        return super.awaitTermination(timeout, unit);
                    }

                    @Override
                    public boolean isShutdown() {
                        var stopped = super.isShutdown();
                        if (inject.get() && shutdownStarted.get()) {
                            events.add("state");
                            stateCalls.incrementAndGet();
                            ExceptionHelper.sneakyThrow(queryFailure);
                        }
                        return stopped;
                    }
                };
        var callers = Executors.newSingleThreadExecutor();
        var config = DurableConfig.builder()
                .withInvocationExecutorFactory(() -> users)
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    public void onInvocationStart(InvocationInfo info) {
                        owner.set(Thread.currentThread());
                    }

                    public void onInvocationEnd(InvocationEndInfo info) {
                        endThread.set(Thread.currentThread());
                        end.set(info);
                        ends.incrementAndGet();
                        events.add("end");
                    }
                })
                .build();
        try {
            var result = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        try {
                            if (priorHandlerFatal) throw handlerFatal;
                            return "result";
                        } finally {
                            bodyExited.set(true);
                        }
                    },
                    config));
            var observed = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS))
                    .getCause();
            assertSame(expected, observed, "The first recognized fatal must survive the later state query");
            assertTrue(
                    contains(observed, order == Failures.ORDINARY_THEN_FATAL ? ordinaryShutdown : shutdownFatal),
                    "The shutdown attempt's original failure remains reachable");
            assertTrue(contains(observed, queryFailure), "The later state-query diagnostic remains reachable");
            assertEquals(
                    stage == Stage.SHUTDOWN
                            ? List.of("end", "shutdown", "state")
                            : List.of("end", "shutdown", "await", "state"),
                    events);
            assertTrue(endBeforeShutdown.get(), "Owned cleanup follows actual body and End completion");
            assertEquals(1, shutdownCalls.get(), "Automatic close cannot repeat finalized shutdown callbacks");
            assertEquals(1, stateCalls.get());
            assertEquals(1, ends.get());
            assertSame(owner.get(), endThread.get());
            assertEquals(
                    priorHandlerFatal ? InvocationStatus.RETRYING : InvocationStatus.SUCCEEDED,
                    end.get().invocationStatus());
            assertSame(priorHandlerFatal ? handlerFatal : null, end.get().executionError());
            if (priorHandlerFatal) {
                assertTrue(workerEscaped.await(3, TimeUnit.SECONDS));
                assertSame(handlerFatal, workerFailure.get());
            } else assertNull(workerFailure.get());
        } finally {
            inject.set(false);
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static boolean contains(Throwable root, Throwable expected) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var failure = pending.removeFirst();
            if (failure == expected) return true;
            if (!seen.add(failure)) continue;
            if (failure.getCause() != null) pending.add(failure.getCause());
            Collections.addAll(pending, failure.getSuppressed());
        }
        return false;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/ordering/exec",
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
