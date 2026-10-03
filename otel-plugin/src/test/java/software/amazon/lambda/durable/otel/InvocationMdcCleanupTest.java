// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class InvocationMdcCleanupTest {
    private static final String TRACE_ID = "12345678901234567890123456789012";

    @ParameterizedTest
    @CsvSource({"true,input", "false,input", "true,factory", "false,factory", "true,start", "false,start"})
    void startupFailureClearsMdcOnTheOwningWorker(boolean executionView, String stage) throws Exception {
        var fatal = new InternalError("startup hook");
        var uncaught = new AtomicReference<Throwable>();
        var escapedMdc = new AtomicReference<String>();
        var escaped = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "startup-mdc-owner");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((owner, failure) -> {
                escapedMdc.set(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
                uncaught.set(failure);
                escaped.countDown();
            });
            return thread;
        });
        var config = OtelPluginConfig.builder()
                .enableMdc(true)
                .contextExtractor(
                        () -> new ExtractedContext(TRACE_ID, "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        var otel = executionView
                ? ExecutionOtelPlugin.factory(SdkTracerProvider.builder(), config)
                : InvocationOtelPlugin.factory(SdkTracerProvider.builder(), config);
        var startMdc = new AtomicReference<String>();
        var ends = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        DurableExecutionPluginFactory observer = info -> new DurableExecutionPlugin() {
            public void onInvocationStart(InvocationInfo value) {
                startMdc.set(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
            }

            public void onInvocationEnd(InvocationEndInfo value) {
                ends.incrementAndGet();
                end.set(value);
            }
        };
        DurableExecutionPluginFactory faulty = info -> {
            if (stage.equals("factory")) throw fatal;
            return new DurableExecutionPlugin() {
                public void onInvocationStart(InvocationInfo value) {
                    if (stage.equals("start")) throw fatal;
                }
            };
        };
        var builder = DurableConfig.builder().withExecutorService(executor).withPlugins(otel, observer, faulty);
        if (stage.equals("input"))
            builder.withSerDes(new SerDes() {
                private final JacksonSerDes delegate = new JacksonSerDes();

                public String serialize(Object value) {
                    return delegate.serialize(value);
                }

                public <T> T deserialize(String data, TypeToken<T> type) {
                    throw new IllegalArgumentException("bad input");
                }
            });
        var bodies = new AtomicInteger();
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        bodies.incrementAndGet();
                        return "unreachable";
                    },
                    builder.build());
            if (stage.equals("input")) {
                assertEquals(ExecutionStatus.FAILED, runner.run("input").getStatus());
                assertNull(
                        executor.submit(() -> MDC.get(MdcSpanEnricher.MDC_TRACE_ID))
                                .get(2, TimeUnit.SECONDS),
                        "startup failure must not leave a trace on the reused handler worker");
            } else {
                assertSame(fatal, assertThrows(InternalError.class, () -> runner.run("input")));
                assertTrue(escaped.await(2, TimeUnit.SECONDS));
                assertSame(fatal, uncaught.get());
                assertNull(escapedMdc.get(), "cleanup must run before the fatal escapes its owner thread");
            }
            assertEquals(TRACE_ID, startMdc.get(), "later startup plugins must still see the invocation trace");
            assertEquals(0, bodies.get());
            assertEquals(1, ends.get());
            assertEquals(
                    stage.equals("input") ? InvocationStatus.FAILED : InvocationStatus.RETRYING,
                    end.get().invocationStatus());
            if (!stage.equals("input")) assertSame(fatal, end.get().executionError());
        } finally {
            executor.shutdownNow();
        }
    }
}
