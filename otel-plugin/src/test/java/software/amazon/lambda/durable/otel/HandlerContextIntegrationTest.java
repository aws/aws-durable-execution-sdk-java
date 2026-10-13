// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class HandlerContextIntegrationTest {
    private static final String TRACE_ID = "12345678901234567890123456789012";

    @ParameterizedTest
    @CsvSource({"true,success", "false,success", "true,failure", "false,failure", "true,suspension", "false,suspension"
    })
    void reusedHandlerWorkerHasNoLeakedScope(boolean executionView, String outcome) throws Exception {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var pluginConfig = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(
                        () -> new ExtractedContext(TRACE_ID, "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPlugin plugin = executionView
                ? new ExecutionOtelPlugin(builder, pluginConfig)
                : new InvocationOtelPlugin(builder, pluginConfig);
        var executor = Executors.newSingleThreadExecutor();
        var config = DurableConfig.builder()
                .withExecutorService(executor)
                .withPlugins(plugin)
                .build();
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, ctx) -> {
                        assertEquals(TRACE_ID, Span.current().getSpanContext().getTraceId());
                        if (outcome.equals("failure")) throw new IllegalStateException("user failure");
                        if (outcome.equals("suspension")) ctx.wait("resume", Duration.ofSeconds(1));
                        return "done";
                    },
                    config);
            var result = runner.run("input");
            assertEquals(
                    switch (outcome) {
                        case "failure" -> ExecutionStatus.FAILED;
                        case "suspension" -> ExecutionStatus.PENDING;
                        default -> ExecutionStatus.SUCCEEDED;
                    },
                    result.getStatus());
            assertFalse(
                    executor.submit(() -> Span.current().getSpanContext().isValid())
                            .get(5, TimeUnit.SECONDS),
                    "invocation-end cleanup must restore the handler worker before the response returns");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void preservesCompatibleAmbientAndRestoresUnrelatedAmbientAfterFailure(boolean executionView, boolean sameTrace) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var pluginConfig = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(
                        () -> new ExtractedContext(TRACE_ID, "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPlugin plugin = executionView
                ? new ExecutionOtelPlugin(builder, pluginConfig)
                : new InvocationOtelPlugin(builder, pluginConfig);
        var ambient = SpanContext.create(
                sameTrace ? TRACE_ID : "abcdefabcdefabcdefabcdefabcdefab",
                "abcdefabcdefabcd",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        var previous = Context.current();
        try (var ignored = Span.wrap(ambient).makeCurrent()) {
            plugin.onInvocationStart(new InvocationInfo(
                    "req",
                    "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/name/id",
                    true,
                    Instant.now()));
            try {
                var active = Span.current().getSpanContext();
                assertEquals(TRACE_ID, active.getTraceId());
                if (sameTrace) assertEquals(ambient, active);
                else assertNotEquals(ambient.getSpanId(), active.getSpanId());
            } finally {
                plugin.onInvocationEnd(new InvocationEndInfo(
                        "req", "arn", true, InvocationStatus.FAILED, new IllegalStateException("handler failure")));
            }
            assertEquals(ambient, Span.current().getSpanContext());
        }
        assertSame(previous, Context.current());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void rootContextIsValidAndRestoredAcrossNestedWorkAndResume(boolean executionView, boolean sampled) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var pluginConfig = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        TRACE_ID,
                        "1234567890123456",
                        sampled ? ExtractedContext.Sampling.SAMPLED : ExtractedContext.Sampling.NOT_SAMPLED))
                .build();
        DurableExecutionPlugin plugin = executionView
                ? new ExecutionOtelPlugin(builder, pluginConfig)
                : new InvocationOtelPlugin(builder, pluginConfig);
        var executor = Executors.newCachedThreadPool();
        var config = DurableConfig.builder()
                .withExecutorService(executor)
                .withPlugins(plugin)
                .build();
        var userProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var userTracer = userProvider.get("user");
        var roots = new ArrayList<SpanContext>();
        var bodyCalls = new AtomicInteger();
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, ctx) -> {
                        var root = Span.current().getSpanContext();
                        assertTrue(root.isValid(), "root handler must have a valid context without an agent");
                        assertEquals(TRACE_ID, root.getTraceId());
                        assertEquals(sampled, root.isSampled());
                        roots.add(root);
                        userTracer.spanBuilder("user-handler").startSpan().end();
                        ctx.step("before", String.class, step -> {
                            bodyCalls.incrementAndGet();
                            assertNotEquals(
                                    root.getSpanId(),
                                    Span.current().getSpanContext().getSpanId());
                            return "before";
                        });
                        assertEquals(root, Span.current().getSpanContext());
                        ctx.runInChildContext("child", String.class, child -> {
                            assertNotEquals(
                                    root.getSpanId(),
                                    Span.current().getSpanContext().getSpanId());
                            return "child";
                        });
                        assertEquals(root, Span.current().getSpanContext());
                        userTracer
                                .spanBuilder("user-handler-restored")
                                .startSpan()
                                .end();
                        ctx.wait("resume", Duration.ofSeconds(1));
                        assertEquals(root, Span.current().getSpanContext());
                        userTracer
                                .spanBuilder("user-handler-after-resume")
                                .startSpan()
                                .end();
                        return "done";
                    },
                    config);
            var first = runner.run("input");
            assertEquals(ExecutionStatus.PENDING, first.getStatus());
            runner.advanceTime();
            var last = runner.run("input");
            assertEquals(ExecutionStatus.SUCCEEDED, last.getStatus());
            assertEquals(2, roots.size());
            assertEquals(1, bodyCalls.get());
            assertFalse(Span.current().getSpanContext().isValid());
            if (sampled) {
                var rootName = executionView ? "Workflow" : "Invocation";
                var spans = exporter.getFinishedSpanItems();
                var userSpans = spans.stream()
                        .filter(span -> span.getName().startsWith("user-handler"))
                        .toList();
                assertEquals(5, userSpans.size());
                for (var span : userSpans) {
                    assertEquals(TRACE_ID, span.getTraceId());
                    assertTrue(roots.stream().anyMatch(root -> root.getSpanId().equals(span.getParentSpanId())));
                }
                for (var root : roots) {
                    assertTrue(spans.stream()
                            .anyMatch(s -> rootName.equals(s.getName())
                                    && root.getSpanId().equals(s.getSpanId())));
                }
            } else {
                assertTrue(exporter.getFinishedSpanItems().isEmpty());
            }
        } finally {
            executor.shutdownNow();
            userProvider.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void customPluginScopesUnwindBeforeOtelScopeOnTheReusedWorker(boolean executionView, boolean suspend)
            throws Exception {
        var key = ContextKey.<String>named("application-context");
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var settings = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(
                        () -> new ExtractedContext(TRACE_ID, "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPlugin delegate = executionView
                ? new ExecutionOtelPlugin(builder, settings)
                : new InvocationOtelPlugin(builder, settings);
        var ends = new ArrayList<String>();
        var otel = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                delegate.onInvocationStart(info);
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                ends.add("otel");
                delegate.onInvocationEnd(info);
            }
        };
        var custom = new DurableExecutionPlugin() {
            private Scope scope;

            @Override
            public void onInvocationStart(InvocationInfo info) {
                scope = Context.current().with(key, "active").makeCurrent();
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                ends.add("custom");
                assertEquals("active", Context.current().get(key));
                scope.close();
            }
        };
        var workers = Executors.newSingleThreadExecutor();
        try {
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        assertEquals("active", Context.current().get(key));
                        assertEquals(TRACE_ID, Span.current().getSpanContext().getTraceId());
                        if (suspend) context.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    },
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(otel, custom)
                            .build());
            for (var invocation = 0; invocation < 2; invocation++) {
                runner.run("input");
                assertEquals(List.of("custom", "otel"), ends);
                ends.clear();
                assertFalse(workers.submit(() -> Span.current().getSpanContext().isValid())
                        .get(3, TimeUnit.SECONDS));
                assertNull(workers.submit(() -> Context.current().get(key)).get(3, TimeUnit.SECONDS));
                runner.advanceTime();
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
