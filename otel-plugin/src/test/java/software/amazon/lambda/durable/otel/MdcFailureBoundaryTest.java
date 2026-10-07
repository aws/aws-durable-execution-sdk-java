// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        "false,false,exception", "true,false,exception", "false,true,exception", "true,true,exception",
        "false,false,fatal", "true,false,fatal", "false,true,fatal", "true,true,fatal"
    })
    void workerMdcRestorationFailureCannotStrandTheInvocation(boolean ambientMdc, boolean suspend, String failureKind)
            throws Exception {
        var original = MDC.getMDCAdapter();
        var injected = new AtomicBoolean();
        var plugin = new RecordingPlugin();
        Throwable failure = failureKind.equals("fatal")
                ? new InternalError("worker MDC restoration failed")
                : new IllegalStateException("worker MDC restoration failed");
        var ownerFailure = new AtomicReference<Throwable>();
        var observed = new CountDownLatch(1);
        var workers = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(
                    () -> {
                        if (ambientMdc) MDC.put("ambient", "saved");
                        else MDC.clear();
                        task.run();
                    },
                    "mdc-restoration-worker");
            thread.setUncaughtExceptionHandler((owner, error) -> {
                ownerFailure.set(error);
                observed.countDown();
            });
            return thread;
        });
        try {
            replaceAdapter(proxy(original, method -> {
                if (Thread.currentThread().getName().equals("mdc-restoration-worker")
                        && plugin.ends.get() == 1
                        && method.equals(ambientMdc ? "setContextMap" : "clear")
                        && injected.compareAndSet(false, true)) throw failure;
                return null;
            }));
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        if (suspend) context.wait("resume", Duration.ofSeconds(1));
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(plugin)
                            .build());
            var result = runner.run("input");
            assertEquals(suspend ? ExecutionStatus.PENDING : ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals(1, plugin.starts.get());
            assertEquals(1, plugin.ends.get());
            assertTrue(injected.get());
            assertTrue(observed.await(3, TimeUnit.SECONDS));
            assertSame(failure, ownerFailure.get(), "restoration failure still escapes its worker after End");
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            replaceAdapter(original);
        }
    }

    @FunctionalInterface
    private interface Fault {
        Object apply(String method) throws Throwable;
    }

    private static MDCAdapter proxy(MDCAdapter delegate, Fault fault) {
        return (MDCAdapter) Proxy.newProxyInstance(
                MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                    var value = fault.apply(method.getName());
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
