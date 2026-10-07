// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.HandlerScoped;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

class FatalScopeCleanupBudgetTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("removal")
    void laterFatalStillWaitsForEarlierScopeCleanupBeforeEndDispatch(boolean threadDeath) throws Exception {
        var enteredEarlier = new CountDownLatch(1);
        var releaseEarlier = new CountDownLatch(1);
        var endCalled = new CountDownLatch(1);
        var scopeClosed = new AtomicBoolean();
        var endedBeforeClose = new AtomicBoolean();
        var ends = new AtomicInteger();
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("later scope close");
        var callers = Executors.newSingleThreadExecutor();
        var workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                super.execute(command);
                // Let both the fatal publication and earlier blocking close happen before caller finalization begins.
                await(enteredEarlier);
            }
        };
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withPlugins(
                            info -> new ScopePlugin(
                                    () -> {
                                        enteredEarlier.countDown();
                                        await(releaseEarlier);
                                        scopeClosed.set(true);
                                    },
                                    scopeClosed,
                                    endedBeforeClose,
                                    ends,
                                    endCalled,
                                    fatal),
                            info -> new ScopePlugin(
                                    () -> {
                                        throw fatal;
                                    },
                                    scopeClosed,
                                    endedBeforeClose,
                                    ends,
                                    endCalled,
                                    fatal))
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        context.wait("pause", Duration.ofSeconds(1));
                        return input;
                    },
                    config);
            var result = callers.submit(() -> runner.run("input"));
            assertTrue(enteredEarlier.await(3, TimeUnit.SECONDS));
            assertFalse(endCalled.await(100, TimeUnit.MILLISECONDS), "earlier cleanup must receive its handoff budget");
            releaseEarlier.countDown();
            var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
            assertFalse(endedBeforeClose.get(), "finalization must follow completed in-budget scope cleanup");
            assertEquals(2, ends.get(), "both plugins finalize once with the original fatal");
        } finally {
            releaseEarlier.countDown();
            callers.shutdownNow();
            workers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @HandlerScoped(ScopeOpener.class)
    public static final class ScopePlugin implements DurableExecutionPlugin {
        private final AutoCloseable closer;
        private final AtomicBoolean closed;
        private final AtomicBoolean endedBeforeClose;
        private final AtomicInteger ends;
        private final CountDownLatch endCalled;
        private final Error fatal;

        ScopePlugin(
                AutoCloseable closer,
                AtomicBoolean closed,
                AtomicBoolean endedBeforeClose,
                AtomicInteger ends,
                CountDownLatch endCalled,
                Error fatal) {
            this.closer = closer;
            this.closed = closed;
            this.endedBeforeClose = endedBeforeClose;
            this.ends = ends;
            this.endCalled = endCalled;
            this.fatal = fatal;
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            if (!closed.get()) endedBeforeClose.set(true);
            if (info.invocationStatus() != InvocationStatus.RETRYING || info.executionError() != fatal) {
                endedBeforeClose.set(true);
            }
            ends.incrementAndGet();
            endCalled.countDown();
        }
    }

    public static final class ScopeOpener implements Function<Object, AutoCloseable> {
        @Override
        public AutoCloseable apply(Object value) {
            return ((ScopePlugin) value).closer;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
