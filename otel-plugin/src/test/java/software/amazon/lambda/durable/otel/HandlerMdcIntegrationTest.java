// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class HandlerMdcIntegrationTest {
    private static final String TRACE_ID = "12345678901234567890123456789012";

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "suspension", "inputFailure"})
    void noPluginPathRetainsExistingMdcBehavior(String outcome) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var callerBefore = MDC.getCopyOfContextMap();
        var callerMdc = Map.of(MdcSpanEnricher.MDC_TRACE_ID, "caller-trace", "caller", "retained");
        var workerMdc = ambientMdc();
        try {
            executor.submit(() -> MDC.setContextMap(workerMdc)).get(5, TimeUnit.SECONDS);
            MDC.setContextMap(callerMdc);
            runWithoutPlugins(executor, outcome);
            // The existing factory worker always clears MDC, including startup/input failures.
            var expectedWorker = Map.<String, String>of();
            assertRestored(executor, expectedWorker, callerMdc);
        } finally {
            executor.shutdownNow();
            if (callerBefore == null) MDC.clear();
            else MDC.setContextMap(callerBefore);
        }
    }

    private static void runWithoutPlugins(ExecutorService executor, String outcome) {
        var config = DurableConfig.builder()
                .withExecutorService(executor)
                .withSerDes(outcome.equals("inputFailure") ? failingInputSerDes() : new JacksonSerDes())
                .build();
        assertTrue(config.getPluginFactories().isEmpty());
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    if (outcome.equals("failure")) throw new IllegalStateException("handler failure");
                    if (outcome.equals("suspension")) ctx.wait("pause", Duration.ofSeconds(1));
                    return "done";
                },
                config);
        var expected =
                switch (outcome) {
                    case "failure", "inputFailure" -> ExecutionStatus.FAILED;
                    case "suspension" -> ExecutionStatus.PENDING;
                    default -> ExecutionStatus.SUCCEEDED;
                };
        assertEquals(expected, runner.run("input").getStatus());
    }

    @ParameterizedTest
    @CsvSource({
        "true,success,false", "false,success,false", "true,failure,false", "false,failure,false",
        "true,suspension,false", "false,suspension,false", "true,inputFailure,false", "false,inputFailure,false",
        "true,success,true", "false,success,true", "true,failure,true", "false,failure,true",
        "true,suspension,true", "false,suspension,true", "true,inputFailure,true", "false,inputFailure,true"
    })
    void preservesCallerAndReusedWorkerMdc(boolean executionView, String outcome, boolean ambient) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var callerBefore = MDC.getCopyOfContextMap();
        var callerMdc = Map.of(MdcSpanEnricher.MDC_TRACE_ID, "caller-trace", "caller", "retained");
        var workerMdc = ambient ? ambientMdc() : Map.<String, String>of();
        try {
            executor.submit(() -> MDC.setContextMap(workerMdc)).get(5, TimeUnit.SECONDS);
            MDC.setContextMap(callerMdc);
            runInvocations(executor, executionView, outcome, workerMdc, callerMdc);
        } finally {
            executor.shutdownNow();
            if (callerBefore == null) MDC.clear();
            else MDC.setContextMap(callerBefore);
        }
    }

    private static Map<String, String> ambientMdc() {
        return Map.of(
                MdcSpanEnricher.MDC_TRACE_ID,
                "worker-trace",
                MdcSpanEnricher.MDC_SPAN_ID,
                "worker-span",
                MdcSpanEnricher.MDC_TRACE_SAMPLED,
                "worker-sampled",
                "application",
                "retained");
    }

    private static void runInvocations(
            ExecutorService executor,
            boolean executionView,
            String outcome,
            Map<String, String> workerMdc,
            Map<String, String> callerMdc)
            throws Exception {
        var calls = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    calls.incrementAndGet();
                    assertEquals(TRACE_ID, MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
                    if (outcome.equals("failure")) throw new IllegalStateException("handler failure");
                    if (outcome.equals("suspension")) ctx.wait("resume", Duration.ofSeconds(1));
                    return "done";
                },
                config(executor, executionView, outcome));
        var first = runner.run("input");
        assertEquals(
                switch (outcome) {
                    case "failure", "inputFailure" -> ExecutionStatus.FAILED;
                    case "suspension" -> ExecutionStatus.PENDING;
                    default -> ExecutionStatus.SUCCEEDED;
                },
                first.getStatus());
        assertEquals(outcome.equals("inputFailure") ? 0 : 1, calls.get());
        assertRestored(executor, workerMdc, callerMdc);
        if (outcome.equals("suspension")) {
            runner.advanceTime();
            assertEquals(ExecutionStatus.SUCCEEDED, runner.run("input").getStatus());
            assertEquals(2, calls.get());
            assertRestored(executor, workerMdc, callerMdc);
        }
    }

    private static DurableConfig config(ExecutorService executor, boolean executionView, String outcome) {
        var settings = OtelPluginConfig.builder()
                .enableMdc(true)
                .contextExtractor(
                        () -> new ExtractedContext(TRACE_ID, "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPluginFactory plugin = executionView
                ? ExecutionOtelPlugin.factory(SdkTracerProvider.builder(), settings)
                : InvocationOtelPlugin.factory(SdkTracerProvider.builder(), settings);
        return DurableConfig.builder()
                .withExecutorService(executor)
                .withPlugins(plugin)
                .withSerDes(outcome.equals("inputFailure") ? failingInputSerDes() : new JacksonSerDes())
                .build();
    }

    private static SerDes failingInputSerDes() {
        return new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            @Override
            public String serialize(Object value) {
                return delegate.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> type) {
                if ("\"input\"".equals(data)) throw new IllegalStateException("input failure");
                return delegate.deserialize(data, type);
            }
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "inputFailure"})
    void endHookObservesTaskMdcBeforeReusedWorkerAmbientStateIsRestored(String outcome) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var callerThread = new AtomicReference<Thread>();
        var caller = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "mdc-end-caller");
            callerThread.set(thread);
            return thread;
        });
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var startThread = new AtomicReference<Thread>();
        var endThread = new AtomicReference<Thread>();
        var endMdc = new AtomicReference<Map<String, String>>();
        var ambient = Map.of("worker", "ambient");
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                startThread.set(Thread.currentThread());
                MDC.put("start-hook", "request");
                started.countDown();
                awaitMdcLatch(release);
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                endThread.set(Thread.currentThread());
                var values = MDC.getCopyOfContextMap();
                endMdc.set(values == null ? Map.of() : values);
                MDC.put("end-hook", "must-not-leak");
            }
        };
        try {
            worker.submit(() -> MDC.setContextMap(ambient)).get(3, TimeUnit.SECONDS);
            var config = DurableConfig.builder()
                    .withExecutorService(worker)
                    .withPlugins(info -> plugin)
                    .withSerDes(outcome.equals("inputFailure") ? failingInputSerDes() : new JacksonSerDes())
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        if (outcome.equals("failure")) throw new IllegalStateException("body failure");
                        return "done";
                    },
                    config);
            var result = caller.submit(() -> runner.run("input"));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            awaitMdcCallerJoin(callerThread.get());
            release.countDown();
            assertEquals(
                    outcome.equals("success") ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                    result.get(5, TimeUnit.SECONDS).getStatus());
            assertSame(startThread.get(), endThread.get());
            // Input failure skips DurableLogger; entered handlers retain its established MDC-clearing behavior.
            assertEquals(
                    outcome.equals("inputFailure") ? Map.of("worker", "ambient", "start-hook", "request") : Map.of(),
                    endMdc.get(),
                    "End hooks must not see prematurely restored ambient worker MDC");
            assertEquals(ambient, worker.submit(MDC::getCopyOfContextMap).get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            caller.shutdownNow();
            worker.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void awaitMdcLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void awaitMdcCallerJoin(Thread caller) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (caller.getState() == Thread.State.WAITING
                    && Arrays.stream(caller.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals(CompletableFuture.class.getName())
                                    && frame.getMethodName().equals("join"))) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("Caller did not attach its completion observer before releasing the handler");
    }

    private static void assertRestored(
            ExecutorService executor, Map<String, String> workerMdc, Map<String, String> callerMdc) throws Exception {
        var callerAfter = MDC.getCopyOfContextMap();
        var after = executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS);
        assertAll(
                () -> assertEquals(callerMdc, callerAfter, "finalization must preserve caller MDC"),
                () -> assertEquals(workerMdc, after == null ? Map.of() : after, "worker must regain its original MDC"));
    }
}
