// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class InvocationOutcomeBoundaryTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cleanupWaitContractAlsoAppliesWithoutPlugins(boolean withPlugin, boolean retry) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var original = retryError();
        var config = DurableConfig.builder();
        if (withPlugin) config.withPlugins(new DurableExecutionPlugin() {});
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        if (retry)
                            context.step("retry", String.class, step -> {
                                throw original;
                            });
                        else context.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    } finally {
                        entered.countDown();
                        await(release);
                    }
                },
                config.build());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> runner.run("input"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> response.get(700, TimeUnit.MILLISECONDS));
            release.countDown();
            if (retry)
                assertSame(
                        original,
                        assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS))
                                .getCause());
            else
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(5, TimeUnit.SECONDS).getStatus());
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invocationEndFatalEscapesItsActualWorkerAfterSettlingCaller(boolean death) throws Exception {
        Error fatal = death ? new ThreadDeath() : new InternalError("fatal end cleanup");
        var ownerFailure = new AtomicReference<Throwable>();
        var escaped = new CountDownLatch(1);
        var ends = new AtomicInteger();
        var workers = Executors.newSingleThreadExecutor(task -> {
            var worker = new Thread(task, "end-fatal-owner");
            worker.setUncaughtExceptionHandler((thread, failure) -> {
                ownerFailure.set(failure);
                escaped.countDown();
            });
            return worker;
        });
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, ctx) -> "done",
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(new DurableExecutionPlugin() {
                                @Override
                                public void onInvocationEnd(InvocationEndInfo info) {
                                    ends.incrementAndGet();
                                    throw fatal;
                                }
                            })
                            .build());
            assertSame(fatal, assertThrows(Error.class, () -> runner.run("input")));
            assertTrue(escaped.await(2, TimeUnit.SECONDS), "fatal end cleanup must escape its actual worker");
            assertSame(fatal, ownerFailure.get());
            assertEquals(1, ends.get());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,false", "SUCCEEDED,true", "PENDING,false", "PENDING,true", "RETRYING,false", "RETRYING,true"
    })
    void inlineNonfatalMdcRestorationPreservesSelectedOutcome(String outcome, boolean ambient) throws Exception {
        runInlineMdcFailure(outcome, ambient, new IllegalStateException("restore failed"), null);
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void inlineFatalRestorationStillEscapesWithOriginalIdentity(boolean death, boolean wrapped) throws Exception {
        Error fatal = death ? new ThreadDeath() : new InternalError("fatal restoration");
        runInlineMdcFailure(
                "SUCCEEDED", false, wrapped ? new CompletionException(new ExecutionException(fatal)) : fatal, fatal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unreadable", "cycle", "fatal"})
    void lifecycleDiagnosticTraversalIsBoundedAndPreservesFatalIdentity(String kind) throws Exception {
        var reads = new AtomicInteger();
        var fatal = new InternalError("fatal diagnostic");
        var failure = new CompletionException("diagnostic", null) {
            @Override
            public synchronized Throwable getCause() {
                reads.incrementAndGet();
                if (kind.equals("cycle")) return this;
                if (kind.equals("fatal")) throw fatal;
                throw new IllegalStateException("unreadable diagnostic");
            }
        };
        runInlineMdcFailure("SUCCEEDED", false, failure, kind.equals("fatal") ? fatal : null);
        assertEquals(1, reads.get());
    }

    private static void runInlineMdcFailure(
            String outcome, boolean ambient, Throwable restoreFailure, Error expectedFatal) throws Exception {
        var adapter = MDC.getMDCAdapter();
        var before = MDC.getCopyOfContextMap();
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var injected = new AtomicBoolean();
        var original = retryError();
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<Runnable>()) {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };
        try {
            MDC.clear();
            if (ambient) MDC.put("ambient", "saved");
            replaceAdapter((MDCAdapter) Proxy.newProxyInstance(
                    MDCAdapter.class.getClassLoader(), new Class<?>[] {MDCAdapter.class}, (proxy, method, args) -> {
                        if (!ends.isEmpty()
                                && method.getName().equals(ambient ? "setContextMap" : "clear")
                                && injected.compareAndSet(false, true)) throw restoreFailure;
                        try {
                            return method.invoke(adapter, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }));
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        if (outcome.equals("PENDING")) context.wait("pause", Duration.ofSeconds(1));
                        if (outcome.equals("RETRYING"))
                            context.step("retry", String.class, step -> {
                                throw original;
                            });
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(executor)
                            .withPlugins(new DurableExecutionPlugin() {
                                @Override
                                public void onInvocationEnd(InvocationEndInfo info) {
                                    ends.add(info);
                                }
                            })
                            .build());
            if (expectedFatal != null) assertSame(expectedFatal, assertThrows(Error.class, () -> runner.run("input")));
            else if (outcome.equals("RETRYING"))
                assertSame(
                        original,
                        assertThrows(UnrecoverableDurableExecutionException.class, () -> runner.run("input")));
            else
                assertEquals(
                        ExecutionStatus.valueOf(outcome), runner.run("input").getStatus());
            assertTrue(injected.get());
            assertEquals(1, ends.size());
            assertEquals(InvocationStatus.valueOf(outcome), ends.get(0).invocationStatus());
        } finally {
            executor.shutdownNow();
            replaceAdapter(adapter);
            if (before == null) MDC.clear();
            else MDC.setContextMap(before);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryFailureRemainsRetryingUntilAReplayActuallySucceeds(boolean executionView) {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var options = OtelPluginConfig.builder()
                .contextExtractor(() -> null)
                .enableMdc(false)
                .build();
        DurableExecutionPlugin otel = executionView
                ? new ExecutionOtelPlugin(provider, options)
                : new InvocationOtelPlugin(provider, options);
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var failDelivery = new AtomicBoolean(true);
        var original = new IllegalStateException("cannot deliver result yet");
        var completedSideEffects = new AtomicInteger();
        var serDes = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            @Override
            public String serialize(Object value) {
                if ("done".equals(value) && failDelivery.compareAndSet(true, false)) throw original;
                return delegate.serialize(value);
            }

            @Override
            public <T> T deserialize(String value, TypeToken<T> type) {
                return delegate.deserialize(value, type);
            }
        };
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("saved", String.class, step -> {
                        completedSideEffects.incrementAndGet();
                        return "checkpointed";
                    });
                    return "done";
                },
                DurableConfig.builder()
                        .withSerDes(serDes)
                        .withPlugins(otel, new DurableExecutionPlugin() {
                            @Override
                            public void onInvocationEnd(InvocationEndInfo info) {
                                ends.add(info);
                            }
                        })
                        .build());
        assertSame(original, assertThrows(IllegalStateException.class, () -> runner.run("input")));
        assertEquals(InvocationStatus.RETRYING, ends.get(0).invocationStatus());
        assertSame(original, ends.get(0).executionError());
        assertEquals(
                0,
                exporter.getFinishedSpanItems().stream()
                        .filter(s -> s.getName().equals("Workflow"))
                        .count());
        assertEquals(ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());
        assertEquals(
                List.of(InvocationStatus.RETRYING, InvocationStatus.SUCCEEDED),
                ends.stream().map(InvocationEndInfo::invocationStatus).toList());
        assertEquals(1, completedSideEffects.get());
        assertEquals(
                1,
                exporter.getFinishedSpanItems().stream()
                        .filter(s -> s.getName().equals("Workflow"))
                        .count());
    }

    @Test
    void partialStartStillUnwindsAllConfiguredEndHooksAndRestoresBothThreads() throws Exception {
        var key = ContextKey.<String>named("partial-start-context");
        var order = new CopyOnWriteArrayList<String>();
        var scopes = new Scope[2];
        var bodyCalls = new AtomicInteger();
        var workers = Executors.newSingleThreadExecutor(task -> new Thread(
                () -> {
                    try (var ignored =
                            Context.root().with(key, "worker ambient").makeCurrent()) {
                        task.run();
                    }
                },
                "partial-start-owner"));
        var first = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                order.add("start:first");
                scopes[0] = Context.current().with(key, "first").makeCurrent();
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                order.add("end:first");
                scopes[0].close();
            }
        };
        var failing = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                order.add("start:failing");
                scopes[1] = Context.current().with(key, "failing").makeCurrent();
                throw new AssertionError("start failed after acquiring context");
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                order.add("end:failing");
                scopes[1].close();
            }
        };
        var unstarted = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                order.add("unexpected start");
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                order.add("end:unstarted");
            }
        };
        try (var ignored = Context.current().with(key, "caller ambient").makeCurrent()) {
            var before = Context.current();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        bodyCalls.incrementAndGet();
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(first, failing, unstarted)
                            .build());
            assertEquals(ExecutionStatus.FAILED, runner.run("input").getStatus());
            assertEquals(List.of("start:first", "start:failing", "end:unstarted", "end:failing", "end:first"), order);
            assertEquals(0, bodyCalls.get());
            assertSame(before, Context.current());
            assertEquals(
                    "worker ambient",
                    workers.submit(() -> Context.current().get(key)).get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static UnrecoverableDurableExecutionException retryError() {
        return new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("retry").build(), true);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void replaceAdapter(MDCAdapter adapter) throws Exception {
        var setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
        setter.setAccessible(true);
        setter.invoke(null, adapter);
    }
}
