// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

class PluginPeerCleanupRegressionTest {
    @Test
    void suspensionDoesNotInterruptChildOwners() throws Exception {
        var interruptions = new AtomicInteger();
        var workers =
                new ThreadPoolExecutor(0, 16, 1, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> new Thread(task) {
                    @Override
                    public void interrupt() {
                        interruptions.incrementAndGet();
                        super.interrupt();
                    }
                });
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        var first = context.runInChildContextAsync("first", String.class, child -> {
                            child.wait("pause", Duration.ofSeconds(10));
                            return "a";
                        });
                        var second = context.runInChildContextAsync("second", String.class, child -> {
                            child.wait("pause", Duration.ofSeconds(10));
                            return "b";
                        });
                        return first.get() + second.get();
                    },
                    DurableConfig.builder().withExecutorService(workers).build());
            assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            assertEquals(0, interruptions.get(), "durable suspension must not interrupt operation owners");
            var result = runner.runUntilComplete("input");
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals("ab", result.getResult(String.class));
            assertEquals(0, interruptions.get());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalPeerClosesUserScopeOnOwnerBeforeInvocationEnd(boolean failCleanup) throws Exception {
        var peerEntered = new CountDownLatch(1);
        var releasePeer = new CountDownLatch(1);
        var peerThread = new AtomicReference<Thread>();
        var localScope = new ThreadLocal<Boolean>();
        var scopeAfterTask = new AtomicReference<Boolean>();
        var observedAfterTask = new CountDownLatch(1);
        var peerEnds = new AtomicInteger();
        var healthyEnds = new AtomicInteger();
        var endBeforeInvocation = new AtomicBoolean();
        var wrongOwner = new AtomicBoolean();
        var endError = new AtomicReference<Throwable>();
        var endOutcome = new AtomicReference<UserFunctionOutcome>();
        var fatal = new InternalError("peer fatal");
        var cleanupFatal = new InternalError("cleanup fatal");
        var workers = new ThreadPoolExecutor(0, 16, 1, TimeUnit.SECONDS, new SynchronousQueue<>()) {
            @Override
            public void execute(Runnable task) {
                super.execute(() -> {
                    try {
                        task.run();
                    } catch (InternalError expected) {
                        // A custom executor may contain task failures and reuse its worker.
                    } finally {
                        if (Thread.currentThread() == peerThread.get()) {
                            scopeAfterTask.set(localScope.get());
                            observedAfterTask.countDown();
                        }
                    }
                });
            }
        };
        DurableExecutionPluginFactory observer = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                if ("peer".equals(start.name())) {
                    peerThread.set(Thread.currentThread());
                    localScope.set(true);
                }
                if ("trigger".equals(start.name())) throw fatal;
            }

            @Override
            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                if ("peer".equals(end.name())) {
                    wrongOwner.set(Thread.currentThread() != peerThread.get());
                    endError.set(end.error());
                    endOutcome.set(end.outcome());
                    localScope.remove();
                    peerEnds.incrementAndGet();
                    if (failCleanup) throw cleanupFatal;
                }
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo end) {
                endBeforeInvocation.set(peerEnds.get() == 1);
            }
        };
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(observer, info -> new DurableExecutionPlugin() {
                        @Override
                        public void onUserFunctionEnd(UserFunctionEndInfo end) {
                            if ("peer".equals(end.name())) {
                                assertSame(fatal, end.error());
                                healthyEnds.incrementAndGet();
                            }
                        }
                    })
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        var peer = context.stepAsync("peer", String.class, step -> {
                            peerEntered.countDown();
                            try {
                                if (!releasePeer.await(3, TimeUnit.SECONDS))
                                    throw new AssertionError("peer not interrupted");
                            } catch (InterruptedException interrupted) {
                                throw new IllegalStateException("cooperative peer", interrupted);
                            }
                            return "released";
                        });
                        try {
                            assertTrue(peerEntered.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            throw new AssertionError(failure);
                        }
                        context.stepAsync("trigger", String.class, step -> "unreachable");
                        return peer.get();
                    },
                    config);
            var thrown = assertThrows(Throwable.class, () -> runner.run("input"));
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(thrown));
            assertTrue(observedAfterTask.await(3, TimeUnit.SECONDS));
            assertEquals(1, peerEnds.get(), "every entered user scope must receive its end hook");
            assertEquals(1, healthyEnds.get(), "one failed cleanup cannot skip the remaining plugins");
            assertFalse(wrongOwner.get());
            assertSame(fatal, endError.get());
            assertEquals(UserFunctionOutcome.FAILED, endOutcome.get());
            assertNull(scopeAfterTask.get(), "the reused executor worker must not retain the attempt scope");
            assertTrue(endBeforeInvocation.get(), "cooperative peer cleanup precedes invocation finalization");
            if (failCleanup) assertTrue(Arrays.asList(fatal.getSuppressed()).contains(cleanupFatal));
        } finally {
            releasePeer.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
