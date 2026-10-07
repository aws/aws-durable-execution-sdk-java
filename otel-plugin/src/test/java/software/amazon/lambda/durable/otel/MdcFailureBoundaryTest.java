// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MdcFailureBoundaryTest {
    @Test
    void workerCaptureFailureDoesNotEndAnInvocationThatNeverStarted() throws Exception {
        var original = MDC.getMDCAdapter();
        var injected = new AtomicBoolean();
        var failure = new IllegalStateException("worker MDC capture failed");
        var plugin = new RecordingPlugin();
        var bodyCalls = new AtomicInteger();
        var executor = new CompletingWorker();
        try {
            replaceAdapter(proxy(original, (method) -> {
                if (method.equals("getCopyOfContextMap")
                        && Thread.currentThread().getName().equals("mdc-initialization-worker")
                        && injected.compareAndSet(false, true)) throw failure;
                return null;
            }));
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        bodyCalls.incrementAndGet();
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(executor)
                            .withPlugins(plugin)
                            .build());
            var result = runner.run("input");
            assertTrue(injected.get());
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            assertEquals(failure.getMessage(), result.getError().orElseThrow().errorMessage());
            assertEquals(0, bodyCalls.get());
            assertEquals(0, plugin.starts.get());
            assertEquals(0, plugin.ends.get(), "no end hook may consume stale state before start dispatch");
        } finally {
            try {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                replaceAdapter(original);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "getCopyOfContextMap,false",
        "clear,false",
        "setContextMap,false",
        "getCopyOfContextMap,true",
        "clear,true",
        "setContextMap,true"
    })
    void nonfatalEndMdcFailuresPreserveTheSelectedOutcome(String method, boolean suspend) throws Exception {
        runEndFailure(method, suspend, new IllegalStateException("end MDC failure"));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({
        "getCopyOfContextMap,false",
        "clear,false",
        "setContextMap,false",
        "getCopyOfContextMap,true",
        "clear,true",
        "setContextMap,true"
    })
    void fatalEndMdcFailuresStillEscape(String method, boolean wrapped) throws Exception {
        Error fatal = wrapped ? new ThreadDeath() : new VirtualMachineError("fatal end MDC failure") {};
        runEndFailure(method, false, wrapped ? new CompletionException(new ExecutionException(fatal)) : fatal, fatal);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unreadableOrdinaryCausePreservesSuccessAndSuspension(boolean suspend) throws Exception {
        var reads = new AtomicInteger();
        var failure = new CompletionException("unreadable", null) {
            @Override
            public synchronized Throwable getCause() {
                reads.incrementAndGet();
                throw new IllegalStateException("diagnostic unavailable");
            }
        };
        runEndFailure("getCopyOfContextMap", suspend, failure);
        assertEquals(1, reads.get());
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalCauseAccessorEscapesWithOriginalIdentity(boolean threadDeath) throws Exception {
        var reads = new AtomicInteger();
        Error fatal = threadDeath ? new ThreadDeath() : new VirtualMachineError("fatal accessor") {};
        var failure = new CompletionException("unreadable", null) {
            @Override
            public synchronized Throwable getCause() {
                reads.incrementAndGet();
                throw fatal;
            }
        };
        runEndFailure("setContextMap", false, failure, fatal);
        assertEquals(1, reads.get());
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"reflection,false", "reflection,true", "proxy,false", "proxy,true"})
    void jdkReflectiveWrappersKeepFatalIdentity(String wrapper, boolean threadDeath) throws Exception {
        Error fatal = threadDeath ? new ThreadDeath() : new VirtualMachineError("wrapped fatal") {};
        Throwable failure = wrapper.equals("reflection")
                ? new InvocationTargetException(fatal)
                : new UndeclaredThrowableException(fatal);
        runEndFailure("clear", false, failure, fatal);
    }

    @Test
    void cyclicCauseIsReadOnlyOnceAndPreservesOutcome() throws Exception {
        var reads = new AtomicInteger();
        var cycle = new CompletionException("cycle", null) {
            @Override
            public synchronized Throwable getCause() {
                reads.incrementAndGet();
                return this;
            }
        };
        runEndFailure("getCopyOfContextMap", false, cycle);
        assertEquals(1, reads.get());
    }

    @Test
    void ordinaryApplicationCauseIsNotTreatedAsATransportWrapper() throws Exception {
        runEndFailure("clear", false, new IllegalStateException("ordinary diagnostic", new VirtualMachineError() {}));
    }

    private static void runEndFailure(String method, boolean suspend, Throwable failure) throws Exception {
        runEndFailure(method, suspend, failure, null);
    }

    private static void runEndFailure(String method, boolean suspend, Throwable failure, Error expectedFatal)
            throws Exception {
        var original = MDC.getMDCAdapter();
        var callerBefore = MDC.getCopyOfContextMap();
        var injected = new AtomicBoolean();
        var plugin = new RecordingPlugin();
        var worker = Executors.newSingleThreadExecutor();
        try {
            replaceAdapter(proxy(original, name -> {
                var inEnd = Arrays.stream(Thread.currentThread().getStackTrace())
                        .anyMatch(frame -> frame.getClassName().endsWith(".DurableExecutor")
                                && frame.getMethodName().equals("fireOnInvocationEnd"));
                if (inEnd && name.equals(method) && injected.compareAndSet(false, true)) throw failure;
                // Select both legitimate MDC restoration branches, independently of the finalizer's thread.
                if (inEnd && name.equals("getCopyOfContextMap"))
                    return method.equals("setContextMap") ? Map.of("ambient", "saved") : NullSnapshot.INSTANCE;
                return null;
            }));
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        if (suspend) context.wait("resume", Duration.ofSeconds(1));
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(worker)
                            .withPlugins(plugin)
                            .build());
            if (expectedFatal != null) {
                assertSame(expectedFatal, assertThrows(Error.class, () -> runner.run("input")));
                assertEquals(method.equals("getCopyOfContextMap") ? 0 : 1, plugin.ends.get());
            } else {
                var result = runner.run("input");
                assertEquals(suspend ? ExecutionStatus.PENDING : ExecutionStatus.SUCCEEDED, result.getStatus());
                assertEquals(1, plugin.ends.get());
            }
            assertTrue(injected.get(), "the actual execute() end boundary must exercise the adapter failure");
            assertEquals(1, plugin.starts.get());
        } finally {
            try {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                replaceAdapter(original);
                if (callerBefore == null) MDC.clear();
                else MDC.setContextMap(callerBefore);
            }
        }
    }

    private enum NullSnapshot {
        INSTANCE
    }

    @FunctionalInterface
    private interface Fault {
        Object apply(String method) throws Throwable;
    }

    private static MDCAdapter proxy(MDCAdapter delegate, Fault fault) {
        return (MDCAdapter) Proxy.newProxyInstance(
                MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                    var value = fault.apply(method.getName());
                    if (value == NullSnapshot.INSTANCE) return null;
                    if (value != null) return value;
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }

    private static void replaceAdapter(MDCAdapter adapter) throws Exception {
        var setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
        setter.setAccessible(true);
        setter.invoke(null, adapter);
    }

    private static final class RecordingPlugin implements DurableExecutionPlugin {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();

        @Override
        public void onInvocationStart(InvocationInfo info) {
            starts.incrementAndGet();
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            ends.incrementAndGet();
        }
    }
    /** Executes on a real worker, completing its first task before returning to the invocation caller. */
    private static final class CompletingWorker extends AbstractExecutorService {
        private final ExecutorService delegate =
                Executors.newSingleThreadExecutor(task -> new Thread(task, "mdc-initialization-worker"));
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public void execute(Runnable task) {
            if (!first.compareAndSet(true, false)) {
                delegate.execute(task);
                return;
            }
            var done = new CompletableFuture<Void>();
            delegate.execute(() -> {
                try {
                    task.run();
                } finally {
                    done.complete(null);
                }
            });
            done.orTimeout(3, TimeUnit.SECONDS).join();
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
