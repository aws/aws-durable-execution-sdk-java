// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.HandlerScoped;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

class ScopeFatalSignalTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    @SuppressWarnings("removal")
    void fatalCloseWakesCallerAndStillAllowsBoundedEarlierCleanup(boolean threadDeath, boolean releaseInBudget)
            throws Exception {
        var earlierEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new AtomicBoolean();
        var ownerFatal = new AtomicReference<Throwable>();
        var ownerObserved = new CountDownLatch(1);
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("later scope close");
        var workers = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "scope-fatal-owner");
            thread.setUncaughtExceptionHandler((owner, failure) -> {
                ownerFatal.set(failure);
                ownerObserved.countDown();
            });
            return thread;
        });
        var callers = Executors.newSingleThreadExecutor();
        try {
            var earlier = new Scoped(() -> {
                earlierEntered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                closed.set(true);
            });
            var later = new Scoped(() -> {
                throw fatal;
            });
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withPlugins(info -> earlier, info -> later)
                    .build();
            var runner = LocalDurableTestRunner.create(String.class, (input, context) -> input, config);
            var result = callers.submit(() -> runner.run("input"));
            assertTrue(earlierEntered.await(3, TimeUnit.SECONDS));
            assertThrows(
                    TimeoutException.class,
                    () -> result.get(100, TimeUnit.MILLISECONDS),
                    "earlier cleanup must receive its handoff budget");
            if (releaseInBudget) release.countDown();
            var failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS),
                    "reported fatal must wake the invocation without waiting forever for earlier cleanup");
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
            assertEquals(releaseInBudget, closed.get());
        } finally {
            release.countDown();
            callers.shutdown();
            workers.shutdown();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertTrue(ownerObserved.await(3, TimeUnit.SECONDS));
        assertSame(fatal, ownerFatal.get(), "the fatal still escapes its actual owner");
    }

    @HandlerScoped(Opener.class)
    public static final class Scoped implements DurableExecutionPlugin {
        private final AutoCloseable closer;

        Scoped(AutoCloseable closer) {
            this.closer = closer;
        }
    }

    public static final class Opener implements Function<Object, AutoCloseable> {
        @Override
        public AutoCloseable apply(Object plugin) {
            return ((Scoped) plugin).closer;
        }
    }
}
