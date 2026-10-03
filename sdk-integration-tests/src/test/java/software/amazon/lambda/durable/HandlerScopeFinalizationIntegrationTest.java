// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.HandlerScoped;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class HandlerScopeFinalizationIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedCleanupReturnsTheOriginalOutcomeThenClosesOnItsOwner(boolean terminate) throws Exception {
        var releaseFinally = new CountDownLatch(1);
        var enteredFinally = new CountDownLatch(1);
        var scopeClosed = new CountDownLatch(1);
        var closedAtEnd = new AtomicBoolean();
        var plugin = new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                var owner = Thread.currentThread();
                return () -> {
                    assertSame(owner, Thread.currentThread());
                    scopeClosed.countDown();
                };
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                closedAtEnd.set(scopeClosed.getCount() == 0);
            }
        };
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        if (terminate)
                            context.step("terminate", String.class, step -> {
                                throw new UnrecoverableDurableExecutionException(
                                        ErrorObject.builder()
                                                .errorMessage("retry")
                                                .build(),
                                        true);
                            });
                        else context.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    } finally {
                        enteredFinally.countDown();
                        try {
                            if (!releaseFinally.await(5, TimeUnit.SECONDS)) throw new AssertionError("not released");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                    }
                },
                DurableConfig.builder().withPlugins(plugin).build());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> runner.run("input"));
            assertTrue(enteredFinally.await(5, TimeUnit.SECONDS));
            if (terminate) {
                var failure = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
                assertInstanceOf(UnrecoverableDurableExecutionException.class, failure.getCause());
            } else
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(2, TimeUnit.SECONDS).getStatus());
            assertFalse(closedAtEnd.get());
            assertEquals(1L, scopeClosed.getCount());
        } finally {
            releaseFinally.countDown();
            assertTrue(scopeClosed.await(5, TimeUnit.SECONDS));
            caller.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void finalizationWaitsForAnOpenedScopeWithoutChangingLegacyNoScopeTiming(boolean hasScope, boolean terminate)
            throws Exception {
        var finallyEntered = new CountDownLatch(1);
        var releaseFinally = new CountDownLatch(1);
        var handlerExited = new CountDownLatch(1);
        var scopeClosed = new AtomicBoolean();
        var endCalled = new AtomicBoolean();
        var scopeClosedAtEnd = new AtomicBoolean();
        var plugin = new ScopedPlugin() {
            @Override
            public AutoCloseable openHandlerScope() {
                if (!hasScope) return null;
                var owner = Thread.currentThread();
                return () -> {
                    assertSame(owner, Thread.currentThread());
                    scopeClosed.set(true);
                };
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                scopeClosedAtEnd.set(scopeClosed.get());
                endCalled.set(true);
            }
        };
        var config = DurableConfig.builder().withPlugins(plugin).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        if (terminate) {
                            context.step("terminate", String.class, step -> {
                                throw new UnrecoverableDurableExecutionException(
                                        ErrorObject.builder()
                                                .errorMessage("retry invocation")
                                                .build(),
                                        true);
                            });
                        } else {
                            context.wait("pause", Duration.ofSeconds(1));
                        }
                        return "done";
                    } finally {
                        finallyEntered.countDown();
                        try {
                            if (!releaseFinally.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("finally not released");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        } finally {
                            handlerExited.countDown();
                        }
                    }
                },
                config);
        var caller = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "test-invocation-caller");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var response = caller.submit(() -> runner.run("input"));
            assertTrue(finallyEntered.await(5, TimeUnit.SECONDS));
            if (hasScope) {
                assertThrows(TimeoutException.class, () -> response.get(150, TimeUnit.MILLISECONDS));
                assertFalse(endCalled.get(), "flush must wait for same-thread scope cleanup");
                releaseFinally.countDown();
            }
            if (terminate) {
                var failure = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
                assertInstanceOf(UnrecoverableDurableExecutionException.class, failure.getCause());
            } else {
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(5, TimeUnit.SECONDS).getStatus());
            }
            assertTrue(endCalled.get());
            assertEquals(hasScope, scopeClosedAtEnd.get());
        } finally {
            releaseFinally.countDown();
            assertTrue(handlerExited.await(5, TimeUnit.SECONDS));
            caller.shutdownNow();
        }
    }

    @HandlerScoped(ScopedPlugin.Opener.class)
    private abstract static class ScopedPlugin implements DurableExecutionPlugin {
        public abstract AutoCloseable openHandlerScope();

        public static class Opener implements Function<ScopedPlugin, AutoCloseable> {
            public AutoCloseable apply(ScopedPlugin plugin) {
                return plugin.openHandlerScope();
            }
        }
    }
}
