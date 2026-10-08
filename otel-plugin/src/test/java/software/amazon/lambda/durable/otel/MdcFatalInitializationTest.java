// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MdcFatalInitializationTest {
    @ParameterizedTest
    @MethodSource("fatalCases")
    @SuppressWarnings("removal")
    void fatalCaptureSettlesCallerThenEscapesOwnerBeforeAnyHook(String mode, boolean death, String wrapper)
            throws Exception {
        Error fatal = death ? new ThreadDeath() : new InternalError("fatal capture");
        Throwable failure =
                switch (wrapper) {
                    case "completion" -> new CompletionException(fatal);
                    case "nested" -> new CompletionException(new ExecutionException(fatal));
                    case "reflection" -> new InvocationTargetException(fatal);
                    case "proxy" -> new UndeclaredThrowableException(fatal);
                    default -> fatal;
                };
        runCapture(mode, failure, fatal);
    }

    @ParameterizedTest
    @MethodSource("ordinaryCases")
    void nonfatalInitializationKeepsItsDurableFailurePolicy(String mode, String kind) throws Exception {
        Throwable failure =
                switch (kind) {
                    case "assertion" -> new AssertionError("ordinary capture");
                    case "application-cause" ->
                        new IllegalStateException("ordinary capture", new InternalError("diagnostic"));
                    default -> new IllegalStateException("ordinary capture");
                };
        runCapture(mode, failure, null);
    }

    private static Stream<Arguments> fatalCases() {
        return Stream.of("direct", "async", "precompleted")
                .flatMap(mode -> Stream.of(false, true)
                        .flatMap(death -> Stream.of("direct", "completion", "nested", "reflection", "proxy")
                                .map(wrapper -> Arguments.of(mode, death, wrapper))));
    }

    private static Stream<Arguments> ordinaryCases() {
        return Stream.of("direct", "async", "precompleted")
                .flatMap(mode ->
                        Stream.of("exception", "assertion", "application-cause").map(kind -> Arguments.of(mode, kind)));
    }

    private static void runCapture(String mode, Throwable failure, Error expectedFatal) throws Exception {
        var originalAdapter = MDC.getMDCAdapter();
        var injected = new AtomicBoolean();
        var starts = new AtomicInteger();
        var bodies = new AtomicInteger();
        var ends = new AtomicInteger();
        var callerThread = new AtomicReference<Thread>();
        var workers = new ObservedExecutor(mode);
        var caller = Executors.newSingleThreadExecutor(task -> daemon(task, "capture-caller"));
        try {
            replaceAdapter((MDCAdapter) Proxy.newProxyInstance(
                    MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                        var capture = method.getName().equals("getCopyOfContextMap")
                                && Arrays.stream(Thread.currentThread().getStackTrace())
                                        .anyMatch(frame -> frame.getClassName().endsWith(".DurableExecutor")
                                                && frame.getMethodName().equals("restoreMdcOnClose"));
                        if (capture && injected.compareAndSet(false, true)) throw failure;
                        try {
                            return method.invoke(originalAdapter, args);
                        } catch (InvocationTargetException invocation) {
                            throw invocation.getCause();
                        }
                    }));
            var plugin = new DurableExecutionPlugin() {
                @Override
                public void onInvocationStart(InvocationInfo info) {
                    starts.incrementAndGet();
                }

                @Override
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.incrementAndGet();
                }
            };
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        bodies.incrementAndGet();
                        return "unreachable";
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(plugin)
                            .build());
            var response = caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                return runner.run("input");
            });
            if (expectedFatal != null) {
                var thrown = assertThrows(
                        ExecutionException.class,
                        () -> response.get(3, TimeUnit.SECONDS),
                        "the observation future must settle rather than strand the invocation");
                assertSame(expectedFatal, thrown.getCause());
                assertTrue(workers.escaped.await(3, TimeUnit.SECONDS));
                assertSame(expectedFatal, workers.escape.get());
                if (mode.equals("direct")) assertSame(callerThread.get(), workers.owner.get());
                else {
                    assertNotSame(callerThread.get(), workers.owner.get());
                    assertTrue(workers.uncaught.await(3, TimeUnit.SECONDS));
                    assertSame(expectedFatal, workers.uncaughtFailure.get());
                }
            } else {
                var result = response.get(3, TimeUnit.SECONDS);
                assertEquals(ExecutionStatus.FAILED, result.getStatus());
                assertEquals(
                        failure.getClass().getName(),
                        result.getError().orElseThrow().errorType());
                assertEquals("ordinary capture", result.getError().orElseThrow().errorMessage());
                assertNull(workers.escape.get(), "ordinary initialization policy must not be broadened");
            }
            assertTrue(injected.get());
            assertEquals(0, starts.get());
            assertEquals(0, bodies.get());
            assertEquals(0, ends.get());
        } finally {
            workers.shutdownNow();
            caller.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                replaceAdapter(originalAdapter);
            }
        }
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void replaceAdapter(MDCAdapter adapter) throws Exception {
        var setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
        setter.setAccessible(true);
        setter.invoke(null, adapter);
    }

    private static final class ObservedExecutor extends AbstractExecutorService {
        private final String mode;
        private final AtomicReference<Throwable> escape = new AtomicReference<>();
        private final AtomicReference<Thread> owner = new AtomicReference<>();
        private final CountDownLatch escaped = new CountDownLatch(1);
        private final AtomicReference<Throwable> uncaughtFailure = new AtomicReference<>();
        private final CountDownLatch uncaught = new CountDownLatch(1);
        private final ExecutorService delegate = Executors.newSingleThreadExecutor(task -> {
            var thread = daemon(task, "capture-worker");
            thread.setUncaughtExceptionHandler((worker, failure) -> {
                uncaughtFailure.set(failure);
                uncaught.countDown();
            });
            return thread;
        });

        private ObservedExecutor(String mode) {
            this.mode = mode;
        }

        @Override
        public void execute(Runnable task) {
            var completed = new CountDownLatch(1);
            Runnable observed = () -> {
                owner.set(Thread.currentThread());
                try {
                    task.run();
                } catch (Error failure) {
                    escape.set(failure);
                    escaped.countDown();
                    throw failure;
                } finally {
                    completed.countDown();
                }
            };
            if (mode.equals("direct")) observed.run();
            else {
                delegate.execute(observed);
                if (mode.equals("precompleted")) {
                    try {
                        assertTrue(completed.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
