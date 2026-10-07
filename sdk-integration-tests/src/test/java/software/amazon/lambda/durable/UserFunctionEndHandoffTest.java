// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

class UserFunctionEndHandoffTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fatalWakesCallerWithoutFinalizingInsideTheOperationOwner(boolean exceedsBudget, boolean partialStart)
            throws Exception {
        var fatal = new InternalError("first end fatal");
        var cleanupEntered = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var cleanupExited = new CountDownLatch(1);
        var owner = new AtomicReference<Thread>();
        var endThread = new AtomicReference<Thread>();
        var invocationEnded = new AtomicBoolean();
        var cleanupFinished = new AtomicBoolean();
        var cleanupBeforeEnd = new AtomicBoolean();
        var cleanupOrder = new CopyOnWriteArrayList<String>();
        var bodyCalls = new AtomicInteger();
        DurableExecutionPluginFactory first = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                owner.set(Thread.currentThread());
                if (partialStart) throw fatal;
            }

            @Override
            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                owner.set(Thread.currentThread());
                if (!partialStart) throw fatal;
            }
        };
        DurableExecutionPluginFactory heldCleanup = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                cleanupEntered.countDown();
                while (releaseCleanup.getCount() != 0) {
                    try {
                        releaseCleanup.await();
                    } catch (InterruptedException ignored) {
                        // Exercise an end hook that does not cooperate with the bounded cancellation request.
                    }
                }
                cleanupFinished.set(true);
                cleanupOrder.add("held");
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo end) {
                endThread.set(Thread.currentThread());
                cleanupBeforeEnd.set(cleanupFinished.get());
                invocationEnded.set(true);
            }
        };
        DurableExecutionPluginFactory earlier = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                cleanupOrder.add("earlier");
                cleanupExited.countDown();
            }
        };
        var workers = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "end-handoff-worker");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {});
            return thread;
        });
        var callers = Executors.newSingleThreadExecutor();
        try {
            var plugins = partialStart
                    ? new DurableExecutionPluginFactory[] {earlier, heldCleanup, first}
                    : new DurableExecutionPluginFactory[] {first, heldCleanup, earlier};
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(plugins)
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> context.step("work", String.class, step -> {
                        bodyCalls.incrementAndGet();
                        return "done";
                    }),
                    config);
            var response = callers.submit(() -> runner.run("input"));
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            if (!exceedsBudget) {
                assertFalse(invocationEnded.get(), "cooperative owner cleanup must precede invocation finalization");
                releaseCleanup.countDown();
            }
            var failure = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
            assertEquals(partialStart ? 0 : 1, bodyCalls.get());
            assertTrue(invocationEnded.get());
            if (exceedsBudget)
                assertNotSame(
                        owner.get(), endThread.get(), "a held operation cannot finalize inline ahead of its cleanup");
            assertEquals(!exceedsBudget, cleanupBeforeEnd.get());
            if (exceedsBudget) assertFalse(cleanupFinished.get(), "the caller must not wait for uncooperative cleanup");
            releaseCleanup.countDown();
            assertTrue(cleanupExited.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("held", "earlier"), cleanupOrder, "earlier starts unwind in LIFO order");
        } finally {
            releaseCleanup.countDown();
            callers.shutdownNow();
            workers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
