// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

class UserFunctionEndCleanupTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @SuppressWarnings("removal")
    void earlierEndFatalCannotSkipLaterOtelOwnerCleanup(boolean executionView, boolean threadDeath) throws Exception {
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("first user end");
        var cleanupFatal = new InternalError("later user end");
        var owner = new AtomicReference<Thread>();
        var before = new AtomicReference<SpanContext>();
        var after = new AtomicReference<SpanContext>();
        var afterTask = new AtomicReference<SpanContext>();
        var endOwner = new AtomicReference<Thread>();
        var ends = new AtomicInteger();
        var bodies = new AtomicInteger();
        var exited = new CountDownLatch(1);
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .enableMdc(true)
                .contextExtractor(() -> new ExtractedContext(
                        "12345678901234567890123456789012", "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        var otel = executionView
                ? ExecutionOtelPlugin.factory(provider, config)
                : InvocationOtelPlugin.factory(provider, config);
        DurableExecutionPluginFactory first = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                if ("work".equals(start.name())) {
                    owner.set(Thread.currentThread());
                    before.set(Span.current().getSpanContext());
                }
            }

            @Override
            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                if ("work".equals(end.name())) throw fatal;
            }
        };
        DurableExecutionPluginFactory last = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                if ("work".equals(end.name())) {
                    ends.incrementAndGet();
                    endOwner.set(Thread.currentThread());
                    after.set(Span.current().getSpanContext());
                }
            }
        };
        DurableExecutionPluginFactory secondFailure = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                if ("work".equals(end.name())) throw cleanupFatal;
            }
        };
        var workers = new ThreadPoolExecutor(0, 16, 1, TimeUnit.SECONDS, new SynchronousQueue<>()) {
            @Override
            public void execute(Runnable task) {
                super.execute(() -> {
                    try {
                        task.run();
                    } catch (VirtualMachineError | ThreadDeath expected) {
                        // Custom executors may retain workers after a task reports its fatal to the invocation.
                    } finally {
                        if (Thread.currentThread() == owner.get()) {
                            afterTask.set(Span.current().getSpanContext());
                            exited.countDown();
                        }
                    }
                });
            }
        };
        try {
            var durable = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(first, otel, secondFailure, last)
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> context.step("work", String.class, step -> {
                        bodies.incrementAndGet();
                        return "done";
                    }),
                    durable);
            var thrown = assertThrows(Error.class, () -> runner.run("input"));
            assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(thrown));
            // Resource shutdown may additionally attach its transport failure; retain this cleanup failure once.
            assertEquals(
                    1,
                    Arrays.stream(fatal.getSuppressed())
                            .filter(error -> error == cleanupFatal)
                            .count());
            assertTrue(exited.await(3, TimeUnit.SECONDS));
            assertEquals(1, bodies.get());
            assertEquals(1, ends.get(), "the later observer must receive the same single end event");
            assertSame(owner.get(), endOwner.get());
            assertEquals(
                    before.get(), after.get(), "OTel must restore its attempt scope on the owner before later hooks");
            assertEquals(
                    before.get(), afterTask.get(), "the executor reuse boundary must not retain the attempt scope");
            assertTrue(exporter.getFinishedSpanItems().stream()
                    .anyMatch(span -> span.getName().contains(" attempt ")));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
