// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
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
            // The existing logger clears MDC once entered; failed input never enters that scope.
            var expectedWorker = outcome.equals("inputFailure") ? workerMdc : Map.<String, String>of();
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
        assertTrue(config.getPluginRunner().isEmpty());
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
        DurableExecutionPlugin plugin = executionView
                ? new ExecutionOtelPlugin(SdkTracerProvider.builder(), settings)
                : new InvocationOtelPlugin(SdkTracerProvider.builder(), settings);
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

    private static void assertRestored(
            ExecutorService executor, Map<String, String> workerMdc, Map<String, String> callerMdc) throws Exception {
        var callerAfter = MDC.getCopyOfContextMap();
        var after = executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS);
        assertAll(
                () -> assertEquals(callerMdc, callerAfter, "finalization must preserve caller MDC"),
                () -> assertEquals(workerMdc, after == null ? Map.of() : after, "worker must regain its original MDC"));
    }
}
