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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
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
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

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
                            .withPlugins(info -> plugin)
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

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void workerRestoreFatalBeforeEndDispatchIsObservedByTheCaller(boolean direct, boolean threadDeath)
            throws Exception {
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("outer worker restore");
        var observed = runOuterFailure(direct, null, fatal);
        assertSame(fatal, observed.failure());
        assertEquals(1, observed.ends());
        assertEquals(InvocationStatus.RETRYING, observed.end().invocationStatus());
        assertSame(fatal, observed.end().executionError());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void failedBodyRetainsPrimaryFailureAndSuppressedRestoration(boolean direct, boolean bodyFatal) throws Exception {
        Throwable primary = bodyFatal ? new InternalError("primary body") : new IllegalStateException("primary body");
        var secondary = new InternalError("suppressed restoration");
        var observed = runOuterFailure(direct, primary, secondary);
        if (bodyFatal) assertSame(primary, observed.failure());
        else {
            assertNull(observed.failure());
            assertEquals(ExecutionStatus.FAILED, observed.status());
            assertEquals("primary body", observed.errorMessage());
        }
        assertEquals(1, observed.ends());
        assertSame(primary, observed.end().executionError());
        assertEquals(
                bodyFatal ? InvocationStatus.RETRYING : InvocationStatus.FAILED,
                observed.end().invocationStatus());
        assertTrue(Arrays.asList(primary.getSuppressed()).contains(secondary));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryWorkerRestorationFailureRetainsSuccessfulOutcome(boolean direct) throws Exception {
        var observed = runOuterFailure(direct, null, new IllegalStateException("adapter refused restoration"));
        assertNull(observed.failure());
        assertEquals(ExecutionStatus.SUCCEEDED, observed.status());
        assertEquals(1, observed.ends());
        assertEquals(InvocationStatus.SUCCEEDED, observed.end().invocationStatus());
    }

    private static OuterOutcome runOuterFailure(boolean direct, Throwable bodyFailure, Throwable restoreFailure)
            throws Exception {
        var original = MDC.getMDCAdapter();
        var callerBefore = MDC.getCopyOfContextMap();
        var injected = new AtomicBoolean();
        var ends = new AtomicInteger();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        ExecutorService executor = direct ? new InlineExecutor() : new CompletingWorker();
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                ends.incrementAndGet();
                endInfo.set(info);
            }
        };
        try {
            replaceAdapter(proxy(original, name -> {
                if (isOuterRestore(name) && injected.compareAndSet(false, true)) throw restoreFailure;
                return null;
            }));
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        if (bodyFailure != null) ExceptionHelper.sneakyThrow(bodyFailure);
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(executor)
                            .withPlugins(info -> plugin)
                            .build());
            Error observed = null;
            ExecutionStatus status = null;
            String errorMessage = null;
            try {
                var result = runner.run("input");
                status = result.getStatus();
                errorMessage =
                        result.getError().map(error -> error.errorMessage()).orElse(null);
            } catch (Error failure) {
                observed = failure;
            }
            assertTrue(injected.get(), "The actual outer worker restoration must reach the injected failure");
            return new OuterOutcome(observed, status, errorMessage, endInfo.get(), ends.get());
        } finally {
            try {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                replaceAdapter(original);
                if (callerBefore == null) MDC.clear();
                else MDC.setContextMap(callerBefore);
            }
        }
    }

    private record OuterOutcome(
            Error failure, ExecutionStatus status, String errorMessage, InvocationEndInfo end, int ends) {}

    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postDispatchWorkerFatalEscapesOwnerWithoutRepeatingEnd(boolean threadDeath) throws Exception {
        var original = MDC.getMDCAdapter();
        var callerBefore = MDC.getCopyOfContextMap();
        var startEntered = new CountDownLatch(1);
        var releaseStart = new CountDownLatch(1);
        var restoreEntered = new CountDownLatch(1);
        var releaseRestore = new CountDownLatch(1);
        var ownerObserved = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        var callerThread = new AtomicReference<Thread>();
        var ended = new AtomicInteger();
        var injected = new AtomicBoolean();
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("late outer restore");
        var worker = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "late-mdc-owner");
            thread.setUncaughtExceptionHandler((owner, failure) -> {
                ownerFailure.set(failure);
                ownerObserved.countDown();
            });
            return thread;
        });
        var caller = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "late-mdc-caller");
            callerThread.set(thread);
            return thread;
        });
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                startEntered.countDown();
                awaitGate(releaseStart);
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                assertEquals(InvocationStatus.SUCCEEDED, info.invocationStatus());
                ended.incrementAndGet();
            }
        };
        try {
            replaceAdapter(proxy(original, name -> {
                if (isOuterRestore(name) && injected.compareAndSet(false, true)) {
                    restoreEntered.countDown();
                    awaitGate(releaseRestore);
                    throw fatal;
                }
                return null;
            }));
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> "done",
                    DurableConfig.builder()
                            .withExecutorService(worker)
                            .withPlugins(info -> plugin)
                            .build());
            var result = caller.submit(() -> runner.run("input"));
            awaitGate(startEntered);
            awaitFinalizerJoin(callerThread.get());
            releaseStart.countDown();
            awaitGate(restoreEntered);
            assertEquals(
                    ExecutionStatus.SUCCEEDED, result.get(2, TimeUnit.SECONDS).getStatus());
            assertEquals(1, ended.get());
            releaseRestore.countDown();
            awaitGate(ownerObserved);
            assertSame(fatal, ownerFailure.get());
            assertEquals(1, ended.get());
        } finally {
            releaseStart.countDown();
            releaseRestore.countDown();
            try {
                caller.shutdownNow();
                worker.shutdownNow();
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            } finally {
                replaceAdapter(original);
                if (callerBefore == null) MDC.clear();
                else MDC.setContextMap(callerBefore);
            }
        }
    }

    private static boolean isOuterRestore(String method) {
        if (!method.equals("clear") && !method.equals("setContextMap")) return false;
        var frames = Arrays.stream(Thread.currentThread().getStackTrace()).toList();
        return frames.stream()
                        .anyMatch(frame -> frame.getClassName().endsWith(".DurableExecutor")
                                && frame.getMethodName().startsWith("lambda$restoreMdcOnClose"))
                && frames.stream()
                        .noneMatch(frame -> frame.getClassName().endsWith(".DurableExecutor")
                                && frame.getMethodName().equals("fireOnInvocationEnd"));
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS), "Controlled MDC gate was not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitFinalizerJoin(Thread caller) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (caller.getState() == Thread.State.WAITING
                    && Arrays.stream(caller.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().endsWith(".DurableExecutor")
                                    && frame.getMethodName().equals("finalizeAfterHandlerScopes"))) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("The invocation caller did not attach its finalizer before handler completion");
    }

    private static final class InlineExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
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
                            .withPlugins(info -> plugin)
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
