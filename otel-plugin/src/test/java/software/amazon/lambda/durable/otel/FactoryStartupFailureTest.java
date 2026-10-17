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
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

@Timeout(20)
class FactoryStartupFailureTest {
    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void completedAnchorFlushesAtFailedFactoryBoundaryAndProviderIsReusable(boolean executionView, boolean failStartup)
            throws Exception {
        var original = new IllegalStateException("Invocation processor start failed");
        var failOnce = new AtomicBoolean(failStartup);
        var rootsEnded = new AtomicInteger();
        var flushes = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var bufferedAtFailure = new AtomicInteger();
        var observed = new AtomicReference<Throwable>();
        var published = new CopyOnWriteArrayList<DurableExecutionPlugin>();
        try (var exporter = InMemorySpanExporter.create();
                var batch = BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofHours(1))
                        .build()) {
            var processor = new SpanProcessor() {
                public void onStart(Context parent, ReadWriteSpan span) {
                    if (span.getName().equals("Invocation") && failOnce.compareAndSet(true, false)) {
                        assertEquals(1, rootsEnded.get(), "the root already completed before Invocation starts");
                        assertTrue(exporter.getFinishedSpanItems().isEmpty(), "healthy BSP still holds the root");
                        bufferedAtFailure.incrementAndGet();
                        throw original;
                    }
                }

                public boolean isStartRequired() {
                    return true;
                }

                public void onEnd(ReadableSpan span) {
                    if (span.getName().equals("DurableExecutionRoot")) rootsEnded.incrementAndGet();
                }

                public boolean isEndRequired() {
                    return true;
                }

                public CompletableResultCode forceFlush() {
                    flushes.incrementAndGet();
                    return CompletableResultCode.ofSuccess();
                }

                public CompletableResultCode shutdown() {
                    shutdowns.incrementAndGet();
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
                try {
                    var instance = actual.createPlugin(info);
                    published.add(instance);
                    return instance;
                } catch (Throwable failure) {
                    observed.set(failure);
                    throw failure;
                }
            };
            var config = DurableConfig.builder().withPlugins(observing).build();
            assertSuccessfulHandler(config);
            if (failStartup) {
                assertSame(original, observed.get());
                assertEquals(1, bufferedAtFailure.get());
                assertTrue(published.isEmpty(), "no partially initialized plugin is published");
                assertEquals(1, exporter.getFinishedSpanItems().size(), "completed root must export before return");
                assertEquals(
                        "DurableExecutionRoot",
                        exporter.getFinishedSpanItems().get(0).getName());
            } else {
                assertNull(observed.get());
                assertEquals(1, published.size());
                assertEquals(3, exporter.getFinishedSpanItems().size());
            }
            assertEquals(1, flushes.get());
            assertEquals(0, shutdowns.get());
            awaitBatchFlushRetired(batch);
            assertSuccessfulHandler(config);
            assertEquals(failStartup ? 1 : 2, published.size());
            assertEquals(
                    failStartup ? 4 : 6,
                    exporter.getFinishedSpanItems().size(),
                    "same provider accepts the next invocation");
            assertEquals(2, rootsEnded.get());
            assertEquals(2, flushes.get());
            assertEquals(0, shutdowns.get(), "factory cleanup must never shut down the environment provider");
        }
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({
        "false,ordinary,ordinary", "true,ordinary,ordinary",
        "false,ordinary,assertion", "true,ordinary,assertion",
        "false,ordinary,vm", "true,ordinary,vm",
        "false,ordinary,wrapped-vm", "true,ordinary,wrapped-vm",
        "false,ordinary,death", "true,ordinary,death",
        "false,vm,ordinary", "true,vm,ordinary",
        "false,vm,vm", "true,vm,vm",
        "false,wrapped-vm,vm", "true,wrapped-vm,vm",
        "false,vm,same", "true,vm,same",
        "false,ordinary,same", "true,ordinary,same",
        "false,ordinary,failed-result", "true,ordinary,failed-result"
    })
    void constructionFailureIdentityAndFatalCleanupPrecedenceStayLocal(
            boolean executionView, String primaryKind, String cleanupKind) throws Exception {
        var firstFatal = new InternalError("startup VM fatal");
        var diagnosticReads = new AtomicInteger();
        Throwable primary = switch (primaryKind) {
            case "vm" -> firstFatal;
            case "wrapped-vm" ->
                new CompletionException("wrapped startup fatal", firstFatal) {
                    @Override
                    public synchronized Throwable getCause() {
                        if (diagnosticReads.incrementAndGet() != 1)
                            throw new IllegalStateException("cause already read");
                        return firstFatal;
                    }
                };
            default -> new IllegalStateException("ordinary startup failure");
        };
        Error cleanupFatal = cleanupKind.equals("death") ? new ThreadDeath() : new InternalError("flush VM fatal");
        Throwable cleanup = switch (cleanupKind) {
            case "vm", "death" -> cleanupFatal;
            case "wrapped-vm" -> new CompletionException(cleanupFatal);
            case "assertion" -> new AssertionError("ordinary factory cleanup Error");
            case "same" -> primary;
            case "failed-result" -> null;
            default -> new IllegalArgumentException("ordinary flush failure");
        };
        var failStart = new AtomicBoolean(true);
        var failFlush = new AtomicBoolean(true);
        var flushes = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var observed = new AtomicReference<Throwable>();
        var suppressedAtFactory = new AtomicReference<Throwable[]>();
        var published = new AtomicInteger();
        try (var exporter = InMemorySpanExporter.create();
                var batch = BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofHours(1))
                        .build()) {
            var processor = new SpanProcessor() {
                public void onStart(Context parent, ReadWriteSpan span) {
                    if (span.getName().equals("Invocation") && failStart.compareAndSet(true, false)) {
                        if (primary instanceof Error error) throw error;
                        throw (RuntimeException) primary;
                    }
                }

                public boolean isStartRequired() {
                    return true;
                }

                public void onEnd(ReadableSpan span) {}

                public boolean isEndRequired() {
                    return false;
                }

                public CompletableResultCode forceFlush() {
                    flushes.incrementAndGet();
                    if (failFlush.compareAndSet(true, false)) {
                        if (cleanup instanceof Error error) throw error;
                        if (cleanup != null) throw (RuntimeException) cleanup;
                        return CompletableResultCode.ofFailure();
                    }
                    return CompletableResultCode.ofSuccess();
                }

                public CompletableResultCode shutdown() {
                    shutdowns.incrementAndGet();
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
                try {
                    var plugin = actual.createPlugin(info);
                    published.incrementAndGet();
                    return plugin;
                } catch (Throwable failure) {
                    observed.set(failure);
                    suppressedAtFactory.set(failure.getSuppressed());
                    throw failure;
                }
            };
            var config = DurableConfig.builder().withPlugins(observing).build();
            boolean fatalCleanup =
                    cleanupKind.equals("vm") || cleanupKind.equals("wrapped-vm") || cleanupKind.equals("death");
            boolean originalFatal = !primaryKind.equals("ordinary");
            Throwable expected = originalFatal ? firstFatal : fatalCleanup ? cleanupFatal : primary;
            if (expected instanceof Error)
                assertSame(expected, assertThrows(Error.class, () -> assertSuccessfulHandler(config)));
            else assertSuccessfulHandler(config);
            assertSame(expected, observed.get());
            if (primaryKind.equals("wrapped-vm")) assertEquals(1, diagnosticReads.get());
            assertEquals(0, published.get());
            assertEquals(originalFatal ? 0 : 1, flushes.get(), "only nonfatal startup failures attempt cleanup flush");
            if (originalFatal) {
                assertEquals(0, suppressedAtFactory.get().length, "no cleanup ran before original fatal propagation");
                // The startup-cleanup poison was never invoked. Keep the later ordinary End a healthy reuse control.
                failFlush.set(false);
            } else if (expected == cleanupFatal)
                assertTrue(Arrays.asList(suppressedAtFactory.get()).contains(primary));
            else if (cleanup != null && cleanup != primary)
                assertTrue(Arrays.asList(suppressedAtFactory.get()).contains(cleanup));
            else
                assertEquals(
                        0, suppressedAtFactory.get().length, "no factory self suppression or synthetic flush failure");
            assertEquals(0, shutdowns.get());
            awaitBatchFlushRetired(batch);
            assertSuccessfulHandler(config);
            assertEquals(1, published.get());
            assertEquals(originalFatal ? 1 : 2, flushes.get());
            assertEquals(4, exporter.getFinishedSpanItems().size(), "next End can flush any prior completed spans too");
            assertEquals(0, shutdowns.get());
        }
    }

    private static void awaitBatchFlushRetired(BatchSpanProcessor batch) throws Exception {
        // BSP 1.67 completes forceFlush before clearing its in-progress slot. A new invocation can otherwise reuse
        // that completed result without flushing its spans. Keep both export-at-return assertions strict, but wait
        // for this test-owned processor's prior flush to retire before exercising sequential provider reuse.
        var workerField = BatchSpanProcessor.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        var worker = workerField.get(batch);
        var pendingField = worker.getClass().getDeclaredField("flushRequested");
        pendingField.setAccessible(true);
        var pending = (AtomicReference<?>) pendingField.get(worker);
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pending.get() != null && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(1);
        }
        assertNull(pending.get(), "previous BSP flush must retire before the provider reuse control");
    }

    private static void assertSuccessfulHandler(DurableConfig config) {
        var handler = new DurableHandler<String, String>() {
            @Override
            public DurableConfig getConfiguration() {
                return config;
            }

            @Override
            public String handleRequest(String input, DurableContext context) {
                return "done";
            }
        };
        var result = LocalDurableTestRunner.create(String.class, handler).run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("done", result.getResult(String.class));
    }
}
