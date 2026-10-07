// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestResult;
import software.amazon.lambda.durable.util.ExceptionHelper;

class InvocationFatalPeerCleanupTest {
    static Stream<Arguments> fatalCases() {
        return Stream.of("handler", "output")
                .flatMap(stage -> Stream.of(false, true)
                        .flatMap(plugins -> Stream.of(false, true)
                                .flatMap(ignores -> Stream.of(false, true)
                                        .map(threadDeath -> Arguments.of(stage, plugins, ignores, threadDeath)))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("fatalCases")
    void selectedFatalBoundsPeerCleanupAndFinalizesOnce(
            String stage, boolean plugins, boolean ignoresInterrupt, boolean threadDeath) throws Exception {
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("invocation fatal");
        try (var run = new Run(stage, plugins, ignoresInterrupt, fatal)) {
            var response = run.start();
            await(run.peerEntered);
            var failure = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
            assertTrue(run.interrupted.get(), "selected fatal requests cooperative cancellation");
            assertEquals(!ignoresInterrupt, run.peerExited.get());
            if (plugins) {
                assertEquals(1, run.ends.get());
                assertEquals(InvocationStatus.RETRYING, run.end.get().invocationStatus());
                assertSame(fatal, run.end.get().executionError());
                assertEquals(!ignoresInterrupt, run.peerCleanedAtEnd.get());
                assertFalse(run.wrongOwner.get());
            }
            run.releasePeer.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryRootFailureKeepsExistingPeerWaitAndDoesNotInterrupt(boolean plugins) throws Exception {
        try (var run = new Run("handler", plugins, false, new IllegalStateException("ordinary root failure"))) {
            var response = run.start();
            await(run.peerEntered);
            assertThrows(TimeoutException.class, () -> response.get(100, TimeUnit.MILLISECONDS));
            assertFalse(run.interrupted.get());
            run.releasePeer.countDown();
            var result = response.get(2, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            assertEquals(
                    "ordinary root failure", result.getError().orElseThrow().errorMessage());
            if (plugins) assertEquals(1, run.ends.get());
        }
    }

    private static final class Run implements AutoCloseable {
        final CountDownLatch peerEntered = new CountDownLatch(1);
        final CountDownLatch releasePeer = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean peerExited = new AtomicBoolean();
        final AtomicBoolean peerCleanedAtEnd = new AtomicBoolean();
        final AtomicBoolean wrongOwner = new AtomicBoolean();
        final AtomicReference<Thread> peerOwner = new AtomicReference<>();
        final AtomicInteger peerEnds = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();
        final AtomicReference<InvocationEndInfo> end = new AtomicReference<>();
        final ExecutorService workers = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "invocation-fatal-peer");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((owner, failure) -> {});
            return thread;
        });
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        final LocalDurableTestRunner<String, String> runner;

        Run(String stage, boolean plugins, boolean ignoresInterrupt, Throwable failure) {
            SerDes serde = new SerDes() {
                private final JacksonSerDes delegate = new JacksonSerDes();

                public String serialize(Object value) {
                    if (stage.equals("output") && "root-result".equals(value)) ExceptionHelper.sneakyThrow(failure);
                    return delegate.serialize(value);
                }

                public <T> T deserialize(String value, TypeToken<T> type) {
                    return delegate.deserialize(value, type);
                }
            };
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withSerDes(serde)
                    .withCheckpointDelay(Duration.ZERO);
            if (plugins)
                config.withPlugins(info -> new DurableExecutionPlugin() {
                    @Override
                    public void onUserFunctionEnd(UserFunctionEndInfo info) {
                        if ("peer".equals(info.name())) {
                            wrongOwner.set(Thread.currentThread() != peerOwner.get());
                            peerEnds.incrementAndGet();
                        }
                    }

                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        end.set(info);
                        peerCleanedAtEnd.set(peerEnds.get() == 1);
                        ends.incrementAndGet();
                    }
                });
            runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        context.stepAsync("peer", String.class, step -> {
                            peerOwner.set(Thread.currentThread());
                            peerEntered.countDown();
                            try {
                                while (releasePeer.getCount() != 0) {
                                    try {
                                        releasePeer.await();
                                    } catch (InterruptedException stop) {
                                        interrupted.set(true);
                                        if (!ignoresInterrupt) return "interrupted";
                                    }
                                }
                                return "released";
                            } finally {
                                peerExited.set(true);
                            }
                        });
                        await(peerEntered);
                        if (stage.equals("handler")) ExceptionHelper.sneakyThrow(failure);
                        return "root-result";
                    },
                    config.build());
        }

        Future<TestResult<String>> start() {
            return caller.submit(() -> runner.run("input"));
        }

        @Override
        public void close() throws Exception {
            releasePeer.countDown();
            caller.shutdown();
            workers.shutdown();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "Peer did not enter");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
