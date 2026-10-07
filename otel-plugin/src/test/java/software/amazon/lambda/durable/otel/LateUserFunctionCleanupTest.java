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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class LateUserFunctionCleanupTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerUnwindingAfterFatalReturnStillClosesItsOriginalAttemptScope(boolean executionView) throws Exception {
        var fatal = new InternalError("peer fatal");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var owner = new AtomicReference<Thread>();
        var before = new AtomicReference<SpanContext>();
        var afterTask = new AtomicReference<SpanContext>();
        var endedOn = new AtomicReference<Thread>();
        var endError = new AtomicReference<Throwable>();
        var ends = new AtomicInteger();
        var factories = new AtomicInteger();
        var endedInstance = new AtomicInteger();
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
        DurableExecutionPluginFactory observer = info -> {
            var instance = factories.incrementAndGet();
            return new DurableExecutionPlugin() {
                @Override
                public void onUserFunctionStart(UserFunctionStartInfo start) {
                    if ("peer".equals(start.name())) {
                        owner.set(Thread.currentThread());
                        before.set(Span.current().getSpanContext());
                    }
                }

                @Override
                public void onUserFunctionEnd(UserFunctionEndInfo end) {
                    if ("peer".equals(end.name())) {
                        endedOn.set(Thread.currentThread());
                        endError.set(end.error());
                        ends.incrementAndGet();
                        endedInstance.set(instance);
                    }
                }
            };
        };
        DurableExecutionPluginFactory fault = info -> new DurableExecutionPlugin() {
            @Override
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                if ("trigger".equals(start.name())) throw fatal;
            }
        };
        var workers = new ThreadPoolExecutor(0, 16, 1, TimeUnit.SECONDS, new SynchronousQueue<>()) {
            @Override
            public void execute(Runnable task) {
                super.execute(() -> {
                    try {
                        task.run();
                    } catch (InternalError expected) {
                        // Reusable custom workers make leaked thread-local scope visible after task exit.
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
                    .withPlugins(observer, otel, fault)
                    .build();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        var peer = context.stepAsync("peer", String.class, step -> {
                            entered.countDown();
                            while (release.getCount() != 0) {
                                try {
                                    release.await();
                                } catch (InterruptedException ignored) {
                                    // Hold the actual user body past the invocation's bounded cleanup allowance.
                                }
                            }
                            return "late";
                        });
                        try {
                            assertTrue(entered.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            throw new AssertionError(failure);
                        }
                        context.stepAsync("trigger", String.class, step -> "unreachable");
                        return peer.get();
                    },
                    durable);
            assertSame(fatal, assertThrows(InternalError.class, () -> runner.run("input")));
            assertEquals(0, ends.get(), "the peer is still held after invocation return");
            var other = LocalDurableTestRunner.create(String.class, (input, context) -> "other", durable);
            assertEquals(ExecutionStatus.SUCCEEDED, other.run("other").getStatus());
            assertEquals(2, factories.get(), "a new invocation creates its own plugin while old cleanup is held");
            release.countDown();
            assertTrue(exited.await(3, TimeUnit.SECONDS));
            assertAll(
                    () -> assertEquals(1, ends.get(), "the started attempt must retain its own end-hook recipients"),
                    () -> assertEquals(
                            before.get(), afterTask.get(), "the original OTel scope must close on its owner"));
            assertSame(owner.get(), endedOn.get());
            assertSame(fatal, endError.get());
            assertEquals(1, endedInstance.get(), "late cleanup belongs to the original invocation's plugin");
            assertEquals(2, factories.get(), "owner cleanup does not invoke factories again");
            assertEquals(
                    1,
                    exporter.getFinishedSpanItems().stream()
                            .filter(span -> span.getName().contains("peer")
                                    && span.getName().contains(" attempt "))
                            .count());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
