// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class FactoryFatalStartupHandoffTest {
    static Stream<Arguments> cases() {
        return Stream.of(false, true)
                .flatMap(execution -> Stream.of(false, true)
                        .flatMap(death ->
                                Stream.of(false, true).map(wrapped -> Arguments.of(execution, death, wrapped))));
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("cases")
    void originalFatalReachesCallerAndEscapesWorkerBeforeAnyAdditionalFlush(
            boolean executionView, boolean death, boolean wrapped) throws Exception {
        Error original = death ? new ThreadDeath() : new InternalError("original startup VM fatal");
        var throwing = new CountDownLatch(1);
        var releaseFlush = new CountDownLatch(1);
        var escaped = new CountDownLatch(1);
        var flushEntered = new AtomicInteger();
        var flushReturned = new AtomicInteger();
        var workerFailure = new AtomicReference<Throwable>();
        var bodies = new AtomicInteger();
        var published = new AtomicInteger();
        var workers = Executors.newSingleThreadExecutor(task -> {
            var worker = new Thread(task, "factory-fatal-owner");
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((thread, failure) -> {
                workerFailure.set(failure);
                escaped.countDown();
            });
            return worker;
        });
        var caller = Executors.newSingleThreadExecutor();
        try (var exporter = InMemorySpanExporter.create();
                var batch = BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofHours(1))
                        .build()) {
            var processor = new SpanProcessor() {
                public void onStart(Context parent, ReadWriteSpan span) {
                    if (!span.getName().equals("Invocation")) return;
                    throwing.countDown();
                    if (wrapped) throw new CompletionException(new ExecutionException(original));
                    throw original;
                }

                public boolean isStartRequired() {
                    return true;
                }

                public void onEnd(ReadableSpan span) {}

                public boolean isEndRequired() {
                    return false;
                }

                public CompletableResultCode forceFlush() {
                    flushEntered.incrementAndGet();
                    try {
                        if (!releaseFlush.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("flush gate not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    flushReturned.incrementAndGet();
                    return CompletableResultCode.ofSuccess();
                }
            };
            var builder = SdkTracerProvider.builder().addSpanProcessor(batch).addSpanProcessor(processor);
            var settings = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            var actual = executionView
                    ? ExecutionOtelPlugin.factory(builder, settings)
                    : InvocationOtelPlugin.factory(builder, settings);
            DurableExecutionPluginFactory observing = info -> {
                var instance = actual.createPlugin(info);
                published.incrementAndGet();
                return instance;
            };
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withPlugins(observing)
                    .build();
            var handler = new DurableHandler<String, String>() {
                @Override
                public DurableConfig getConfiguration() {
                    return config;
                }

                @Override
                public String handleRequest(String input, DurableContext context) {
                    bodies.incrementAndGet();
                    return "unreachable";
                }
            };
            var runner = LocalDurableTestRunner.create(String.class, handler);
            var result = caller.submit(() -> runner.run("input"));
            try {
                assertTrue(throwing.await(3, TimeUnit.SECONDS), "actual factory never reached Invocation start");
                Throwable beforeRelease = null;
                boolean timedOut = false;
                try {
                    result.get(2, TimeUnit.SECONDS);
                    fail("startup fatal unexpectedly returned a handler result");
                } catch (ExecutionException failure) {
                    beforeRelease = ExceptionHelper.unwrapAsyncFailure(failure);
                } catch (TimeoutException blocked) {
                    timedOut = true;
                }
                boolean workerBeforeRelease = !timedOut && escaped.await(2, TimeUnit.SECONDS);
                int enteredBeforeRelease = flushEntered.get();
                int returnedBeforeRelease = flushReturned.get();
                assertEquals(1, releaseFlush.getCount(), "observations must precede latch release");
                releaseFlush.countDown();
                var afterRelease = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                assertSame(original, ExceptionHelper.unwrapAsyncFailure(afterRelease));
                assertTrue(escaped.await(3, TimeUnit.SECONDS));
                assertSame(original, workerFailure.get(), "original fatal must escape the actual asynchronous worker");
                assertEquals(0, bodies.get());
                assertEquals(0, published.get());
                System.out.printf(
                        "HANDOFF execution=%s death=%s wrapped=%s callerBefore=%s workerBefore=%s flushEntered=%s flushReturned=%s callerAfterSame=true workerSame=true%n",
                        executionView,
                        death,
                        wrapped,
                        beforeRelease == original,
                        workerBeforeRelease,
                        enteredBeforeRelease,
                        returnedBeforeRelease);
                assertSame(
                        original,
                        beforeRelease,
                        "caller must observe the original fatal before the flush gate is released");
                assertTrue(workerBeforeRelease, "worker fatal escape must precede additional cleanup");
                assertEquals(0, enteredBeforeRelease, "known original fatal must not call additional forceFlush");
            } finally {
                releaseFlush.countDown();
            }
        } finally {
            releaseFlush.countDown();
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
