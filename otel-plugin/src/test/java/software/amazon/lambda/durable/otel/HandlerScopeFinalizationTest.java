// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.HandlerScoped;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class HandlerScopeFinalizationTest {
    @ParameterizedTest
    @CsvSource({
        "false,false,false",
        "false,false,true",
        "false,true,false",
        "false,true,true",
        "true,false,false",
        "true,false,true",
        "true,true,false",
        "true,true,true"
    })
    void preservesTimeForRealOtelFinalization(boolean executionView, boolean retry, boolean hasScope) throws Exception {
        var deadline = new Deadline();
        var clock = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "flush-clock"));
        var flush = new DelayedFlush(deadline, clock);
        var builder = SdkTracerProvider.builder().addSpanProcessor(flush);
        var settings = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        "12345678901234567890123456789012", "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPlugin delegate = executionView
                ? new ExecutionOtelPlugin(builder, settings)
                : new InvocationOtelPlugin(builder, settings);
        var plugin = new ScopedPlugin() {
            public AutoCloseable openHandlerScope() {
                if (!hasScope) return null;
                if (delegate instanceof InvocationOtelPlugin invocation)
                    return new InvocationOtelPlugin.HandlerScopeOpener().apply(invocation);
                return new ExecutionOtelPlugin.HandlerScopeOpener().apply((ExecutionOtelPlugin) delegate);
            }

            public void onInvocationStart(InvocationInfo info) {
                delegate.onInvocationStart(info);
            }

            public void onInvocationEnd(InvocationEndInfo info) {
                delegate.onInvocationEnd(info);
            }
        };
        var workers = Executors.newCachedThreadPool(r -> daemon(r, "handler-owner"));
        var callers = Executors.newSingleThreadExecutor(r -> daemon(r, "invocation-caller"));
        var enteredFinally = new CountDownLatch(1);
        var releaseFinally = new CountDownLatch(1);
        var original = retryError();
        var cfg = config(workers, plugin);
        try {
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    deadline.context(),
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        deadline.arm(400);
                        try {
                            if (retry)
                                ctx.step("retry", String.class, step -> {
                                    throw original;
                                });
                            else ctx.wait("pause", Duration.ofSeconds(1));
                            return "done";
                        } finally {
                            enteredFinally.countDown();
                            await(releaseFinally);
                        }
                    },
                    cfg));
            check(enteredFinally.await(2, TimeUnit.SECONDS), "handler finally not reached");
            outcome(response, retry, original);
            long total = deadline.elapsedMillis();
            check(flush.calls.get() == 1, "expected one actual bundled OTel forceFlush");
            check(flush.flushMillis.get() >= 240, "actual bundled OTel join did not await forceFlush result");
            check(total < 400, "unexpected response/deadline relation: " + total);
            check(releaseFinally.getCount() == 1, "test cleanup was not blocked");

        } finally {
            releaseFinally.countDown();
            workers.shutdown();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            callers.shutdownNow();
            clock.shutdownNow();
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({
        "false,false,false",
        "false,false,true",
        "false,true,false",
        "false,true,true",
        "true,false,false",
        "true,false,true",
        "true,true,false",
        "true,true,true"
    })
    void scopeFatal(boolean retry, boolean wrapped, boolean threadDeath) throws Exception {
        scopeFatal(retry, wrapped, threadDeath ? new ThreadDeath() : new InternalError("scope fatal"), false);
    }

    @Test
    void losingBodyFinallyFatalPreservesTheOriginalWinner() throws Exception {
        scopeFatal(false, false, new InternalError("body finally fatal"), true);
    }

    @Test
    void fatalAfterTheHandoffTimeoutCannotChangeAnAlreadyReturnedOutcome() throws Exception {
        scopeFatal(false, false, new InternalError("late scope fatal"), false, true);
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void scopeFatalAfterEndDispatchPreservesCallerOutcomeAndEscapesOwner(boolean threadDeath, boolean duringEnd)
            throws Exception {
        var releaseScope = new CountDownLatch(1);
        var ownerFinished = new CountDownLatch(1);
        var shutdownEntered = new AtomicBoolean();
        var ended = new AtomicBoolean();
        var endCalls = new AtomicInteger();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var fatalObservedDuringEnd = new AtomicBoolean();
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("late handler scope failure");
        var ownerFatal = new AtomicReference<Throwable>();
        var fatalEscaped = new CountDownLatch(1);
        var plugin = new ScopedPlugin() {
            public AutoCloseable openHandlerScope() {
                return () -> {
                    await(releaseScope);
                    throw fatal;
                };
            }

            public void onInvocationEnd(InvocationEndInfo info) {
                ended.set(true);
                endCalls.incrementAndGet();
                endInfo.set(info);
                if (duringEnd) {
                    releaseScope.countDown();
                    await(fatalEscaped);
                    fatalObservedDuringEnd.set(ownerFatal.get() == fatal);
                }
            }
        };
        var workers =
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                    var owner = daemon(task, "shutdown-owner");
                    owner.setUncaughtExceptionHandler((thread, failure) -> {
                        ownerFatal.set(failure);
                        fatalEscaped.countDown();
                    });
                    return owner;
                }) {
                    @Override
                    public void execute(Runnable task) {
                        super.execute(() -> {
                            try {
                                task.run();
                            } finally {
                                ownerFinished.countDown();
                            }
                        });
                    }

                    @Override
                    public int getActiveCount() {
                        // ExecutionManager.close reaches this after invocation-end callbacks. Hold resource closure
                        // until the owner has reported the fatal, without altering the handler/control futures.
                        assertTrue(ended.get());
                        shutdownEntered.set(true);
                        releaseScope.countDown();
                        await(ownerFinished);
                        return super.getActiveCount();
                    }
                };
        var deadline = new Deadline();
        try {
            var output = assertDoesNotThrow(() -> DurableExecutor.execute(
                    input(),
                    deadline.context(),
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        deadline.arm(0);
                        ctx.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    },
                    config(workers, plugin)));
            assertEquals(ExecutionStatus.PENDING, output.status(), "the already finalized caller outcome stays frozen");
            assertTrue(fatalEscaped.await(2, TimeUnit.SECONDS), "fatal must still escape the actual owner thread");
            assertSame(fatal, ownerFatal.get());
            assertEquals(duringEnd, fatalObservedDuringEnd.get(), "blocked end hook must observe the owner fatal");
            assertTrue(shutdownEntered.get());
            assertEquals(1, endCalls.get(), "a late fatal must not replay already delivered end hooks");
            assertEquals(
                    InvocationStatus.PENDING,
                    endInfo.get().invocationStatus(),
                    "snapshot reflects outcome known at dispatch");
            assertNull(endInfo.get().executionError(), "the fatal is reported after the selected end snapshot");
        } finally {
            releaseScope.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    static void scopeFatal(boolean retry, boolean wrapped, Error fatal, boolean fromBodyFinally) throws Exception {
        scopeFatal(retry, wrapped, fatal, fromBodyFinally, false);
    }

    static void scopeFatal(boolean retry, boolean wrapped, Error fatal, boolean fromBodyFinally, boolean afterTimeout)
            throws Exception {
        var deadline = new Deadline();
        var closeEntered = new CountDownLatch(1);
        var releaseFatal = new CountDownLatch(1);
        var fatalRaised = new CountDownLatch(1);
        var fatalBeforeEnd = new AtomicBoolean();
        var plugin = new ScopedPlugin() {
            public AutoCloseable openHandlerScope() {
                var owner = Thread.currentThread();
                return () -> {
                    check(owner == Thread.currentThread(), "wrong scope close thread");
                    if (!fromBodyFinally) raiseFatal(closeEntered, releaseFatal, fatalRaised, wrapped, fatal);
                };
            }

            public void onInvocationEnd(InvocationEndInfo info) {
                fatalBeforeEnd.set(fatalRaised.getCount() == 0);
            }
        };
        var workers = Executors.newCachedThreadPool(r -> daemon(r, "fatal-owner"));
        var callers = Executors.newSingleThreadExecutor(r -> daemon(r, "fatal-caller"));
        var original = retryError();
        var cfg = config(workers, plugin);
        try {
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    deadline.context(),
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        deadline.arm(30_000);
                        try {
                            if (retry)
                                ctx.step("retry", String.class, step -> {
                                    throw original;
                                });
                            else ctx.wait("pause", Duration.ofSeconds(1));
                            return "done";
                        } finally {
                            if (fromBodyFinally) raiseFatal(closeEntered, releaseFatal, fatalRaised, wrapped, fatal);
                        }
                    },
                    cfg));
            check(closeEntered.await(2, TimeUnit.SECONDS), "fatal site not reached");
            check(deadline.budgetRead.await(2, TimeUnit.SECONDS), "winner not observed before fatal");
            if (afterTimeout) {
                outcome(response, retry, original);
                assertFalse(fatalBeforeEnd.get());
                releaseFatal.countDown();
                assertTrue(fatalRaised.await(2, TimeUnit.SECONDS));
                outcome(response, retry, original);
                return;
            }
            releaseFatal.countDown();
            if (fromBodyFinally) {
                outcome(response, retry, original);
                assertTrue(fatalBeforeEnd.get());
            } else {
                var thrown = assertThrows(ExecutionException.class, () -> response.get(3, TimeUnit.SECONDS));
                assertSame(fatal, thrown.getCause(), "scope-owned fatal must escape the invocation caller");
            }

        } finally {
            releaseFatal.countDown();
            workers.shutdown();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            callers.shutdownNow();
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void legacyBodyFatalRetainsFailedMapping(boolean threadDeath) {
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("body fatal");
        var workers = Executors.newCachedThreadPool(r -> daemon(r, "body-fatal"));
        try {
            var output = DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        throw fatal;
                    },
                    config(workers));
            check(output.status() == ExecutionStatus.FAILED, "legacy body fatal not FAILED");
            check(output.error().errorType().equals(fatal.getClass().getName()), "legacy body fatal error type lost");

        } finally {
            workers.shutdownNow();
        }
    }

    static void raiseFatal(
            CountDownLatch entered, CountDownLatch release, CountDownLatch raised, boolean wrapped, Error fatal) {
        entered.countDown();
        await(release);
        raised.countDown();
        if (wrapped) throw new CompletionException(new ExecutionException(fatal));
        throw fatal;
    }

    static void outcome(Future<DurableExecutionOutput> response, boolean retry, Throwable original) throws Exception {
        if (retry) {
            try {
                response.get(3, TimeUnit.SECONDS);
                throw new AssertionError("retry returned output");
            } catch (ExecutionException e) {
                check(e.getCause() == original, "retry failure changed to " + e.getCause());
            }
        } else check(response.get(3, TimeUnit.SECONDS).status() == ExecutionStatus.PENDING, "suspension changed");
    }

    static DurableConfig config(ExecutorService workers, DurableExecutionPlugin... plugins) {
        return DurableConfig.builder()
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withPlugins(plugins)
                .build();
    }

    static UnrecoverableDurableExecutionException retryError() {
        return new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("original retry").build(), true);
    }

    static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .name("test")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.now())
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/name/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    static Thread daemon(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    static void await(CountDownLatch latch) {
        try {
            check(latch.await(3, TimeUnit.SECONDS), "fixture latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("owner interrupted", e);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static class Deadline {
        volatile long start;
        volatile long end;
        final CountDownLatch budgetRead = new CountDownLatch(1);

        void arm(int millis) {
            start = System.nanoTime();
            end = start + TimeUnit.MILLISECONDS.toNanos(millis);
        }

        long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }

        int remaining() {
            return end == 0 ? 30_000 : (int) Math.max(0L, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
        }

        Context context() {
            return (Context) Proxy.newProxyInstance(
                    Context.class.getClassLoader(), new Class<?>[] {Context.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getRemainingTimeInMillis")) {
                            budgetRead.countDown();
                            return remaining();
                        }
                        if (method.getName().equals("getAwsRequestId")) return "test-request";
                        if (method.getName().equals("getMemoryLimitInMB")) return 512;
                        return null;
                    });
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
