// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

class PluginFatalRunningPeerTest {
    @ParameterizedTest
    @CsvSource({"false,false,true", "true,false,true", "false,true,true", "true,true,true", "false,false,false"})
    @SuppressWarnings("removal")
    void runningPeerCleanupIsBoundedOnlyAfterPluginFatal(
            boolean threadDeath, boolean ignoresInterrupt, boolean triggerFatal) throws Exception {
        var peerEntered = new CountDownLatch(1);
        var releasePeer = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var exited = new AtomicBoolean();
        var ends = new AtomicInteger();
        var manager = new AtomicReference<ExecutionManager>();
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("peer hook fatal");
        DurableExecutionPluginFactory fault = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                if ("trigger".equals(start.name())) throw fatal;
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo end) {
                ends.incrementAndGet();
            }
        };
        var workers = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "running-peer-worker");
            thread.setDaemon(true);
            return thread;
        });
        var callers = Executors.newSingleThreadExecutor();
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(fault)
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        manager.set(((DurableContextImpl) context).getExecutionManager());
                        var peer = context.stepAsync("peer", String.class, step -> {
                            peerEntered.countDown();
                            try {
                                while (releasePeer.getCount() != 0) {
                                    try {
                                        releasePeer.await();
                                    } catch (InterruptedException error) {
                                        interrupted.set(true);
                                        if (!ignoresInterrupt) return "interrupted";
                                    }
                                }
                                return "released";
                            } finally {
                                exited.set(true);
                            }
                        });
                        await(peerEntered);
                        if (triggerFatal) {
                            context.stepAsync("trigger", String.class, step -> "unreachable");
                            return peer.get();
                        }
                        return input;
                    },
                    config);
            var response = callers.submit(() -> runner.run("input"));
            assertTrue(peerEntered.await(3, TimeUnit.SECONDS));
            if (triggerFatal) {
                var failure = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
                assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
                assertTrue(interrupted.get(), "the running peer receives cooperative cancellation");
                assertEquals(
                        !ignoresInterrupt,
                        exited.get(),
                        "uncooperative work must not hold invocation shutdown forever");
            } else {
                assertThrows(TimeoutException.class, () -> response.get(100, TimeUnit.MILLISECONDS));
                assertFalse(interrupted.get(), "ordinary shutdown must not interrupt user work");
                releasePeer.countDown();
                assertEquals(
                        ExecutionStatus.SUCCEEDED,
                        response.get(2, TimeUnit.SECONDS).getStatus());
                assertTrue(exited.get());
            }
            assertEquals(1, ends.get());
            manager.get().getPluginRunner().onInvocationEnd(null);
            assertEquals(1, ends.get(), "the completed invocation must release its plugin instances");
        } finally {
            releasePeer.countDown();
            workers.shutdownNow();
            callers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("peer not started");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
