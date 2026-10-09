// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.util.ExceptionHelper;

class ExecutionRootLifecycleTest {
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/root/id";
    private static final Instant START = Instant.ofEpochSecond(10);

    @ParameterizedTest
    @CsvSource({
        "false,start-failure", "true,start-failure",
        "false,end-failure", "true,end-failure",
        "false,fatal-root-cleanup", "true,fatal-root-cleanup",
        "false,reentrant-end", "true,reentrant-end"
    })
    void rootIsClosedOnceAcrossPartialStartEndFailureReentryAndReuse(boolean executionView, String mode) {
        var original = Context.current();
        var plugin = new AtomicReference<DurableExecutionPlugin>();
        var failOnce = new AtomicBoolean(true);
        var rootFatalOnce = new AtomicBoolean(true);
        var inRootCallback = new AtomicBoolean();
        var rootsEnded = new AtomicInteger();
        var flushes = new AtomicInteger();
        var starts = new ArrayList<String>();
        var roots = new ArrayList<ReadWriteSpan>();
        var primary = new AssertionError("invocation processor failure");
        var cleanupFatal = new InternalError("root cleanup fatal");
        var observer = new SpanProcessor() {
            public void onStart(Context parent, ReadWriteSpan span) {
                starts.add(span.getName());
                if (span.getName().equals("DurableExecutionRoot")) roots.add(span);
                if (span.getName().equals("Invocation")
                        && mode.equals("start-failure")
                        && failOnce.compareAndSet(true, false)) throw primary;
            }

            public boolean isStartRequired() {
                return true;
            }

            public void onEnd(ReadableSpan span) {
                if (span.getName().equals("Invocation")
                        && mode.contains("failure")
                        && !mode.equals("start-failure")
                        && failOnce.compareAndSet(true, false)) throw primary;
                if (span.getName().equals("Invocation")
                        && mode.equals("fatal-root-cleanup")
                        && failOnce.compareAndSet(true, false)) throw primary;
                if (!span.getName().equals("DurableExecutionRoot")) return;
                rootsEnded.incrementAndGet();
                inRootCallback.set(true);
                try {
                    if (mode.equals("reentrant-end")) plugin.get().onInvocationEnd(end(false));
                    if (mode.equals("fatal-root-cleanup") && rootFatalOnce.compareAndSet(true, false))
                        throw cleanupFatal;
                } finally {
                    inRootCallback.set(false);
                }
            }

            public boolean isEndRequired() {
                return true;
            }

            public CompletableResultCode forceFlush() {
                assertFalse(inRootCallback.get(), "Reentrant End must not flush recursively from root end callbacks");
                flushes.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
        };
        try (var exporter = InMemorySpanExporter.create()) {
            var builder = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .addSpanProcessor(observer);
            var config = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            plugin.set(
                    executionView
                            ? new ExecutionOtelPlugin(builder, config)
                            : new InvocationOtelPlugin(builder, config));
            var first = new InvocationInfo("first", ARN, true, START);
            if (mode.equals("start-failure"))
                assertSame(
                        primary,
                        assertThrows(AssertionError.class, () -> plugin.get().onInvocationStart(first)));
            else plugin.get().onInvocationStart(first);
            assertEquals("DurableExecutionRoot", starts.get(0));
            assertTrue(roots.get(0).isRecording(), "The root is retained until invocation end");
            if (mode.equals("end-failure"))
                assertSame(
                        primary,
                        assertThrows(AssertionError.class, () -> plugin.get().onInvocationEnd(end(true))));
            else if (mode.equals("fatal-root-cleanup")) {
                assertSame(
                        cleanupFatal,
                        assertThrows(InternalError.class, () -> plugin.get().onInvocationEnd(end(true))));
                assertEquals(List.of(primary), List.of(cleanupFatal.getSuppressed()));
            } else plugin.get().onInvocationEnd(end(true));
            assertEquals(1, rootsEnded.get());
            assertFalse(roots.get(0).isRecording());
            assertEquals(
                    mode.equals("fatal-root-cleanup") ? 0 : 1,
                    flushes.get(),
                    "Normal and partial-start cleanup flush only after the root ends; fatal root cleanup escapes");
            var beforeDuplicateEnd = flushes.get();
            plugin.get().onInvocationEnd(end(true));
            assertEquals(beforeDuplicateEnd, flushes.get());
            assertEquals(1, rootsEnded.get());

            plugin.get().onInvocationStart(new InvocationInfo("second", ARN, false, START));
            plugin.get().onInvocationEnd(end(false));
            assertEquals(2, rootsEnded.get());
            assertEquals(roots.get(0).getSpanContext(), roots.get(1).getSpanContext());
            var exportedRoots = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("DurableExecutionRoot"))
                    .toList();
            assertEquals(2, exportedRoots.size());
            for (var root : exportedRoots) {
                assertEquals(10_000_000_000L, root.getStartEpochNanos());
                assertEquals(root.getStartEpochNanos(), root.getEndEpochNanos());
            }
            assertSame(original, Context.current());
        }
    }

    private static InvocationEndInfo end(boolean first) {
        return new InvocationEndInfo("request", ARN, first, InvocationStatus.PENDING, null);
    }

    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({
        "runtime,ordinary",
        "runtime,fatal",
        "linkage,ordinary",
        "linkage,fatal",
        "assertion,unused",
        "vm,unused",
        "death,unused"
    })
    void flushRunsOnlyAfterRootFailuresIsolatedByTheExistingPluginBoundary(String rootKind, String flushKind) {
        Throwable rootFailure =
                switch (rootKind) {
                    case "linkage" -> new LinkageError("root linkage");
                    case "assertion" -> new AssertionError("root assertion");
                    case "vm" -> new InternalError("root fatal");
                    case "death" -> new ThreadDeath();
                    default -> new IllegalStateException("root ordinary");
                };
        Throwable flushFailure = flushKind.equals("fatal")
                ? new InternalError("flush fatal")
                : new IllegalArgumentException("flush ordinary");
        var root = mock(Span.class);
        doAnswer(call -> {
                    ExceptionHelper.sneakyThrow(rootFailure);
                    return null;
                })
                .when(root)
                .end(START);
        var flushes = new AtomicInteger();
        var observed = assertThrows(
                Throwable.class,
                () -> OtelPluginSupport.endRootAndFlush(root, START, () -> {
                    flushes.incrementAndGet();
                    ExceptionHelper.sneakyThrow(flushFailure);
                }));
        var isolated = rootKind.equals("runtime") || rootKind.equals("linkage");
        assertEquals(isolated ? 1 : 0, flushes.get());
        var expected = isolated && flushKind.equals("fatal") ? flushFailure : rootFailure;
        assertSame(expected, observed);
        assertEquals(
                isolated ? List.of(expected == rootFailure ? flushFailure : rootFailure) : List.of(),
                List.of(observed.getSuppressed()));
        verify(root).end(START);
    }
}
