// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.PluginRunner;

class InvocationEndFailureTest {
    @ParameterizedTest
    @MethodSource("failures")
    @SuppressWarnings("removal")
    void restoresContextWhenInvocationEndFails(boolean executionView, String phase, String failureKind)
            throws Exception {
        Throwable failure =
                switch (failureKind) {
                    case "exception" -> new IllegalStateException("telemetry failed");
                    case "linkage" -> new NoClassDefFoundError("incompatible telemetry dependency");
                    case "fatal" -> new InternalError("fatal telemetry failure");
                    default -> new ThreadDeath();
                };
        var processor = new FailingProcessor(phase, failure);
        var builder = SdkTracerProvider.builder().addSpanProcessor(processor);
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        "12345678901234567890123456789012", "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPlugin plugin =
                executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
        var healthyEnds = new AtomicInteger();
        var healthy = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                healthyEnds.incrementAndGet();
            }
        };
        var runner = new PluginRunner(List.of(healthy, plugin));
        var ambient = Span.wrap(SpanContext.create(
                "abcdefabcdefabcdefabcdefabcdefab",
                "abcdefabcdefabcd",
                TraceFlags.getSampled(),
                TraceState.getDefault()));
        try (var ignored = ambient.makeCurrent()) {
            var original = Context.current();
            runner.onInvocationStart(new InvocationInfo("req", "arn", true, Instant.now()));
            assertNotSame(original, Context.current());
            if (phase.equals("scope")) {
                var field = plugin.getClass().getDeclaredField("handlerScope");
                field.setAccessible(true);
                var scope = (Scope) field.get(plugin);
                field.set(plugin, (Scope) () -> {
                    scope.close();
                    raise(failure);
                });
            }
            var end = new InvocationEndInfo("req", "arn", true, InvocationStatus.SUCCEEDED, null);
            var fatal = failureKind.equals("fatal") || failureKind.equals("thread-death");
            if (fatal) assertSame(failure, assertThrows(Error.class, () -> runner.onInvocationEnd(end)));
            else assertDoesNotThrow(() -> runner.onInvocationEnd(end));
            assertSame(original, Context.current(), "telemetry errors must not leave the handler context attached");
            assertEquals(1, healthyEnds.get(), "remaining invocation-end hooks must always release their resources");
            // Cleanup state is consumed even if the scope's close implementation throws.
            assertDoesNotThrow(() -> plugin.onInvocationEnd(end));
            assertSame(original, Context.current());
        }
    }

    @ParameterizedTest
    @MethodSource("combinedFailures")
    void preservesCombinedFinalizationAndScopeFailures(boolean executionView, String phase, String pair)
            throws Exception {
        Throwable primary =
                switch (pair.split("-")[0]) {
                    case "fatal" -> new InternalError("primary finalization");
                    case "assert" -> new AssertionError("primary finalization");
                    case "linkage" -> new NoClassDefFoundError("primary finalization");
                    default -> new IllegalStateException("primary finalization");
                };
        Throwable cleanup =
                switch (pair.split("-")[1]) {
                    case "fatal" -> new InternalError("scope cleanup");
                    case "assert" -> new AssertionError("scope cleanup");
                    case "same" -> primary;
                    default -> new IllegalStateException("scope cleanup");
                };
        var cleanupWins = List.of("runtime-fatal", "assert-fatal", "runtime-assert", "linkage-assert")
                .contains(pair);
        var expected = cleanupWins ? cleanup : primary;
        var secondary = cleanupWins ? primary : cleanup;
        var processor = new FailingProcessor(phase, primary);
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        "12345678901234567890123456789012", "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        var builder = SdkTracerProvider.builder().addSpanProcessor(processor);
        DurableExecutionPlugin plugin =
                executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
        var healthyEnds = new AtomicInteger();
        var closed = new AtomicInteger();
        var healthy = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                healthyEnds.incrementAndGet();
            }
        };
        var runner = new PluginRunner(List.of(healthy, plugin));
        var ambient = Span.wrap(SpanContext.create(
                "abcdefabcdefabcdefabcdefabcdefab",
                "abcdefabcdefabcd",
                TraceFlags.getSampled(),
                TraceState.getDefault()));
        try (var ignored = ambient.makeCurrent()) {
            var original = Context.current();
            runner.onInvocationStart(new InvocationInfo("req", "arn", true, Instant.now()));
            var field = plugin.getClass().getDeclaredField("handlerScope");
            field.setAccessible(true);
            var scope = (Scope) field.get(plugin);
            field.set(plugin, (Scope) () -> {
                closed.incrementAndGet();
                scope.close();
                raise(cleanup);
            });
            var end = new InvocationEndInfo("req", "arn", true, InvocationStatus.SUCCEEDED, null);
            if (expected instanceof RuntimeException || expected instanceof LinkageError) {
                assertDoesNotThrow(() -> runner.onInvocationEnd(end));
            } else assertSame(expected, assertThrows(Error.class, () -> runner.onInvocationEnd(end)));
            assertEquals(expected == secondary ? List.of() : List.of(secondary), List.of(expected.getSuppressed()));
            assertSame(original, Context.current());
            assertEquals(1, healthyEnds.get());
            assertEquals(1, closed.get());
            assertDoesNotThrow(() -> plugin.onInvocationEnd(end));
            assertEquals(1, closed.get(), "scope cleanup is one-shot even when both phases throw");
        }
    }

    private static Stream<Arguments> combinedFailures() {
        return Stream.of(false, true)
                .flatMap(view -> Stream.of("span", "flush")
                        .flatMap(phase -> Stream.of(
                                        "fatal-runtime",
                                        "assert-runtime",
                                        "runtime-fatal",
                                        "assert-fatal",
                                        "fatal-fatal",
                                        "fatal-same",
                                        "runtime-assert",
                                        "linkage-assert",
                                        "runtime-runtime")
                                .map(pair -> Arguments.of(view, phase, pair))));
    }

    private static Stream<Arguments> failures() {
        return Stream.of(false, true)
                .flatMap(executionView -> Stream.of("span", "flush", "scope")
                        .flatMap(phase -> Stream.of("exception", "linkage", "fatal", "thread-death")
                                .map(kind -> Arguments.of(executionView, phase, kind))));
    }

    private static void raise(Throwable failure) {
        if (failure instanceof RuntimeException exception) throw exception;
        throw (Error) failure;
    }

    private record FailingProcessor(String phase, Throwable failure) implements SpanProcessor {
        @Override
        public void onStart(Context parent, ReadWriteSpan span) {}

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            if (phase.equals("span")) raise(failure);
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        @Override
        public CompletableResultCode forceFlush() {
            if (phase.equals("flush")) raise(failure);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
