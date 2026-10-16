// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(12)
class RootBodyFatalOwnerLifetimeTest {
    static Stream<Arguments> fatalCases() {
        return Stream.of(false, true)
                .flatMap(death -> Stream.of(false, true)
                        .flatMap(wrapped -> Stream.of("blocked", "different", "same")
                                .map(cleanup -> Arguments.of(death, wrapped, cleanup))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("fatalCases")
    void bodyFatalPublishesBeforeOwnedCleanupAndRetainsFirstIdentity(boolean death, boolean wrapped, String cleanup)
            throws Exception {
        Error fatal = death ? new ThreadDeath() : new InternalError("simulated first body fatal");
        Error second = cleanup.equals("same") ? fatal : new InternalError("simulated cleanup fatal");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var ownerFailure = new AtomicReference<Throwable>();
        var escaped = new CountDownLatch(1);
        var workers = workers(ownerFailure, escaped);
        var caller = Executors.newSingleThreadExecutor();
        var plugin = new ScopedPlugin(
                () -> {
                    var owner = Thread.currentThread();
                    return () -> {
                        assertSame(owner, Thread.currentThread());
                        entered.countDown();
                        if (cleanup.equals("blocked")) await(release);
                        closed.countDown();
                        if (!cleanup.equals("blocked")) throw second;
                    };
                },
                ends::add);
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        ExceptionHelper.sneakyThrow(
                                wrapped ? new CompletionException(new ExecutionException(fatal)) : fatal);
                        return "unreachable";
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(info -> plugin)
                            .build());
            var result = caller.submit(() -> runner.run("input"));
            await(entered);
            if (cleanup.equals("blocked")) {
                assertThrows(TimeoutException.class, () -> result.get(600, TimeUnit.MILLISECONDS));
                assertTrue(ends.isEmpty(), "End must await the root scope owner");
                release.countDown();
            }
            var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
            assertEquals(1, ends.size());
            assertEquals(InvocationStatus.RETRYING, ends.get(0).invocationStatus());
            assertSame(fatal, ends.get(0).executionError());
            if (cleanup.equals("blocked")) assertEquals(0, closed.getCount(), "End and return follow scope closure");
            release.countDown();
            await(escaped);
            assertSame(fatal, ownerFailure.get());
            if (cleanup.equals("different"))
                assertTrue(List.of(fatal.getSuppressed()).contains(second));
            assertFalse(List.of(fatal.getSuppressed()).contains(fatal));
            assertTrue(List.of(fatal.getSuppressed()).stream().noneMatch(IllegalArgumentException.class::isInstance));
        } finally {
            release.countDown();
            stop(caller, workers);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryBodyAndDiagnosticFatalCauseRetainOrdinaryCleanupWait(boolean diagnosticFatal) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var workers = Executors.newSingleThreadExecutor();
        var caller = Executors.newSingleThreadExecutor();
        var body = new IllegalStateException(
                "ordinary body", diagnosticFatal ? new InternalError("diagnostic only") : null);
        var plugin = new ScopedPlugin(
                () -> () -> {
                    entered.countDown();
                    await(release);
                },
                ends::add);
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        throw body;
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(info -> plugin)
                            .build());
            var result = caller.submit(() -> runner.run("input"));
            await(entered);
            assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            assertTrue(ends.isEmpty());
            release.countDown();
            assertEquals(ExecutionStatus.FAILED, result.get(2, TimeUnit.SECONDS).getStatus());
            assertEquals(1, ends.size());
            assertSame(body, ends.get(0).executionError());
        } finally {
            release.countDown();
            stop(caller, workers);
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void losingFinallyFatalUnwindsBeforePendingEndAndEscapesOwner(boolean death) throws Exception {
        Error fatal = death ? new ThreadDeath() : new InternalError("simulated late body fatal");
        var bodyWaiting = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var endEntered = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(1);
        var ends = new AtomicInteger();
        var ownerFailure = new AtomicReference<Throwable>();
        var escaped = new CountDownLatch(1);
        var workers = workers(ownerFailure, escaped);
        var caller = Executors.newSingleThreadExecutor();
        var plugin = new ScopedPlugin(() -> () -> {}, info -> {
            assertEquals(InvocationStatus.PENDING, info.invocationStatus());
            ends.incrementAndGet();
            endEntered.countDown();
            await(releaseEnd);
        });
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        try {
                            context.wait("pause", Duration.ofSeconds(1));
                            return "unused";
                        } finally {
                            bodyWaiting.countDown();
                            await(releaseBody);
                            throw fatal;
                        }
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(info -> plugin)
                            .build());
            var result = caller.submit(() -> runner.run("input"));
            await(bodyWaiting);
            assertThrows(TimeoutException.class, () -> result.get(600, TimeUnit.MILLISECONDS));
            assertEquals(1, endEntered.getCount(), "End cannot start until the body finally unwinds");
            releaseBody.countDown();
            await(endEntered);
            releaseEnd.countDown();
            assertEquals(
                    ExecutionStatus.PENDING, result.get(2, TimeUnit.SECONDS).getStatus());
            assertEquals(1, ends.get());
            await(escaped);
            assertSame(fatal, ownerFailure.get());
        } finally {
            releaseBody.countDown();
            releaseEnd.countDown();
            stop(caller, workers);
        }
    }

    private static ExecutorService workers(AtomicReference<Throwable> failure, CountDownLatch escaped) {
        return Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "root-body-lifetime-owner");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((owner, error) -> {
                failure.set(error);
                escaped.countDown();
            });
            return thread;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled lifetime gate not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void stop(ExecutorService... executors) throws Exception {
        for (var executor : executors) executor.shutdownNow();
        for (var executor : executors) assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }

    @HandlerScoped(ScopedPlugin.Opener.class)
    public static final class ScopedPlugin implements DurableExecutionPlugin {
        private final Supplier<AutoCloseable> open;
        private final Consumer<InvocationEndInfo> end;

        ScopedPlugin(Supplier<AutoCloseable> open, Consumer<InvocationEndInfo> end) {
            this.open = open;
            this.end = end;
        }

        public void onInvocationEnd(InvocationEndInfo info) {
            end.accept(info);
        }

        public static final class Opener implements Function<ScopedPlugin, AutoCloseable> {
            public AutoCloseable apply(ScopedPlugin plugin) {
                return plugin.open.get();
            }
        }
    }
}
