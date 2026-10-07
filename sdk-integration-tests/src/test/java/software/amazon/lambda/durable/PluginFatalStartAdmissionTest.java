// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

class PluginFatalStartAdmissionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("removal")
    void peerFatalDuringStartHookPreventsLaterUserBody(boolean threadDeath) throws Exception {
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("peer fatal during start");
        var startEntered = new CountDownLatch(1);
        var releaseStart = new CountDownLatch(1);
        var peerExited = new CountDownLatch(1);
        var peerOwner = new AtomicReference<Thread>();
        var bodyCalls = new AtomicInteger();
        var workers = new ThreadPoolExecutor(0, 16, 1, TimeUnit.SECONDS, new SynchronousQueue<>()) {
            @Override
            public void execute(Runnable task) {
                super.execute(() -> {
                    try {
                        task.run();
                    } catch (VirtualMachineError | ThreadDeath expected) {
                        // The caller must receive the same fatal; keep this custom executor reusable for observation.
                    } finally {
                        if (Thread.currentThread() == peerOwner.get()) peerExited.countDown();
                    }
                });
            }
        };
        var callers = Executors.newSingleThreadExecutor();
        DurableExecutionPluginFactory plugin = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                if ("blocked".equals(start.name())) {
                    peerOwner.set(Thread.currentThread());
                    startEntered.countDown();
                    boolean interrupted = false;
                    while (releaseStart.getCount() != 0) {
                        try {
                            releaseStart.await();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                if ("trigger".equals(start.name())) throw fatal;
            }
        };
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(plugin)
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        var blocked = context.stepAsync("blocked", String.class, step -> {
                            bodyCalls.incrementAndGet();
                            return "side effect";
                        });
                        try {
                            assertTrue(startEntered.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            throw new AssertionError(failure);
                        }
                        context.stepAsync("trigger", String.class, step -> "unreachable");
                        return blocked.get();
                    },
                    config);
            var result = callers.submit(() -> runner.run("input"));
            var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
            assertEquals(0, bodyCalls.get());
            releaseStart.countDown();
            assertTrue(peerExited.await(3, TimeUnit.SECONDS));
            assertEquals(0, bodyCalls.get(), "a hook returning after the fatal must not admit user side effects");
        } finally {
            releaseStart.countDown();
            callers.shutdownNow();
            workers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
