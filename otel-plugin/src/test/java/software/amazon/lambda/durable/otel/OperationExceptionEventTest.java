// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.testing.time.TestClock;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;

class OperationExceptionEventTest {
    private static final String NAME = "backend-diagnostic";
    private static final AttributeKey<String> TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> STACK = AttributeKey.stringKey("exception.stacktrace");

    static Stream<Arguments> storedErrors() {
        return Stream.of(null, Instant.EPOCH, Instant.ofEpochSecond(20))
                .flatMap(end -> Stream.of(
                        Arguments.of(end, new RuntimeException("ordinary failure"), "java.lang.RuntimeException"),
                        Arguments.of(
                                end,
                                new DurableOperationException(null, null, "no stored error"),
                                DurableOperationException.class.getCanonicalName()),
                        Arguments.of(end, storedFailure(null), DurableOperationException.class.getCanonicalName()),
                        Arguments.of(end, storedFailure("java.lang.RuntimeException"), "java.lang.RuntimeException"),
                        Arguments.of(end, storedFailure("RejectedError"), "RejectedError"),
                        Arguments.of(end, storedFailure(" RejectedError "), " RejectedError "),
                        Arguments.of(end, storedFailure(""), DurableOperationException.class.getCanonicalName()),
                        Arguments.of(end, storedFailure("   "), DurableOperationException.class.getCanonicalName()),
                        Arguments.of(end, storedFailure("\t\r\n"), DurableOperationException.class.getCanonicalName()),
                        Arguments.of(
                                end, storedFailure("\u2003"), DurableOperationException.class.getCanonicalName())));
    }

    @ParameterizedTest
    @MethodSource("storedErrors")
    void storedTypeChangesOnlyTheOperationExceptionType(Instant end, Throwable error, String expectedType) {
        try (var exporter = InMemorySpanExporter.create();
                var provider = SdkTracerProvider.builder()
                        .setClock(TestClock.create(Instant.ofEpochSecond(100)))
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var tracer = provider.get("operation-exception-test");
            var baseline = tracer.spanBuilder("baseline").startSpan();
            baseline.recordException(error);
            baseline.end();
            var span = tracer.spanBuilder(NAME).startSpan();
            OtelPluginSupport.applyOperationStatus(
                    span,
                    new OperationEndInfo(
                            "op", NAME, "CALLBACK", "Callback", null, null, end, "FAILED", null, false, error),
                    end);
            span.end();
            var spans = exporter.getFinishedSpanItems();
            assertEquals(StatusCode.ERROR, spans.get(1).getStatus().getStatusCode());
            assertEquals(error.getMessage(), spans.get(1).getStatus().getDescription());
            assertExceptionDetails(
                    spans.get(0),
                    spans.get(1),
                    expectedType,
                    end == null || end.equals(Instant.EPOCH) ? 100_000_000_000L : 20_000_000_000L);
        }
    }

    private static void assertExceptionDetails(
            SpanData baseline, SpanData actual, String expectedType, long expectedTime) {
        assertEquals(1, actual.getEvents().size());
        var event = actual.getEvents().get(0);
        assertEquals("exception", event.getName());
        assertEquals(
                baseline.getEvents().get(0).getAttributes().toBuilder()
                        .put(TYPE, expectedType)
                        .build()
                        .asMap(),
                event.getAttributes().asMap());
        assertEquals(expectedTime, event.getEpochNanos());
    }

    private static DurableOperationException storedFailure(String type) {
        var error = new DurableOperationException(
                null,
                ErrorObject.builder()
                        .errorType(type)
                        .errorMessage("stored failure")
                        .stackTrace("Example|step|Workflow.java|42")
                        .build(),
                "wrapper message",
                new IllegalArgumentException("cause"));
        error.addSuppressed(new IllegalStateException("suppressed cleanup"));
        return error;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownEndRetainsRealExceptionAndWrapperDiagnostics(boolean wrapped) {
        var cause = new IllegalArgumentException("backend failure");
        cause.setStackTrace(new StackTraceElement[] {new StackTraceElement("Example", "step", "Workflow.java", 42)});
        Throwable error = cause;
        if (wrapped) {
            error = new CompletionException("transport failure", cause);
            error.setStackTrace(new StackTraceElement[] {new StackTraceElement("Worker", "run", "Worker.java", 7)});
            error.addSuppressed(new IllegalStateException("suppressed cleanup"));
        }
        var span = operation(error, SpanLimits.getDefault());
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(error.getMessage(), span.getStatus().getDescription());
        assertEquals(1, span.getEvents().size());
        var event = span.getEvents().get(0);
        assertEquals("exception", event.getName());
        assertEquals(span.getEndEpochNanos(), event.getEpochNanos());
        assertEquals(20_000_000_000L, event.getEpochNanos());
        assertEquals(error.getClass().getCanonicalName(), event.getAttributes().get(TYPE));
        assertEquals(error.getMessage(), event.getAttributes().get(MESSAGE));
        var stack = event.getAttributes().get(STACK);
        assertTrue(stack.startsWith(error.toString()));
        assertTrue(stack.contains("at Example.step(Workflow.java:42)"));
        if (wrapped) {
            assertSame(cause, error.getCause());
            assertTrue(stack.contains("Caused by: java.lang.IllegalArgumentException: backend failure"));
            assertTrue(stack.contains("at Worker.run(Worker.java:7)"));
            assertTrue(stack.contains("Suppressed: java.lang.IllegalStateException: suppressed cleanup"));
        }
    }

    @Test
    void absentMessageIsNotInventedForAnActualThrowable() {
        var event = operation(new IllegalArgumentException(), SpanLimits.getDefault())
                .getEvents()
                .get(0);
        assertEquals(
                IllegalArgumentException.class.getCanonicalName(),
                event.getAttributes().get(TYPE));
        assertNull(event.getAttributes().get(MESSAGE));
        assertTrue(event.getAttributes().get(STACK).startsWith("java.lang.IllegalArgumentException"));
        assertEquals(2, event.getAttributes().size());
        assertEquals(20_000_000_000L, event.getEpochNanos());
    }

    @Test
    void recordingUnsampledSpansStillContainTheTimestampedException() {
        var sampler = mock(Sampler.class);
        when(sampler.shouldSample(any(), anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(SamplingResult.recordOnly());
        var span = finish(new IllegalStateException("backend failure"), SpanLimits.getDefault(), sampler).stream()
                .filter(item -> NAME.equals(item.getName()))
                .findFirst()
                .orElseThrow();
        assertFalse(span.getSpanContext().isSampled());
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(1, span.getEvents().size());
        assertEquals(20_000_000_000L, span.getEvents().get(0).getEpochNanos());
        assertEquals("backend failure", span.getEvents().get(0).getAttributes().get(MESSAGE));
    }

    @Test
    void timestampedExceptionHonorsPerEventAttributeCountRatherThanSpanAttributeCount() {
        var limits = SpanLimits.builder()
                .setMaxNumberOfAttributes(1)
                .setMaxNumberOfAttributesPerEvent(2)
                .build();
        var event = operation(new IllegalStateException("backend failure"), limits)
                .getEvents()
                .get(0);
        assertEquals(2, event.getAttributes().size());
        assertEquals(3, event.getTotalAttributeCount());
        assertEquals(20_000_000_000L, event.getEpochNanos());
    }

    @Test
    void timestampedExceptionHonorsProviderAttributeValueLimit() {
        var limits = SpanLimits.builder().setMaxAttributeValueLength(16).build();
        var event = operation(new IllegalStateException("backend failure with a long message"), limits)
                .getEvents()
                .get(0);
        assertEquals("java.lang.Illega", event.getAttributes().get(TYPE));
        assertEquals("backend failure ", event.getAttributes().get(MESSAGE));
        assertEquals("java.lang.Illega", event.getAttributes().get(STACK));
        assertEquals(3, event.getAttributes().size());
        assertEquals(20_000_000_000L, event.getEpochNanos());
    }

    @Test
    void providerMayDiscardExceptionEventAtItsEventLimit() {
        var span = operation(
                new IllegalStateException("backend failure"),
                SpanLimits.builder().setMaxNumberOfEvents(0).build());
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertTrue(span.getEvents().isEmpty());
        assertEquals(1, span.getTotalRecordedEvents());
    }

    @Test
    void droppedSpansDoNotRenderOrExportExceptionDetails() {
        var rendered = new AtomicInteger();
        var error = new IllegalStateException("backend failure") {
            @Override
            public void printStackTrace(PrintWriter writer) {
                rendered.incrementAndGet();
                super.printStackTrace(writer);
            }
        };
        assertTrue(finish(error, SpanLimits.getDefault(), Sampler.alwaysOff()).isEmpty());
        assertEquals(0, rendered.get());
    }

    private static SpanData operation(Throwable error, SpanLimits limits) {
        return finish(error, limits, Sampler.alwaysOn()).stream()
                .filter(span -> NAME.equals(span.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static List<SpanData> finish(Throwable error, SpanLimits limits, Sampler sampler) {
        try (var exporter = InMemorySpanExporter.create()) {
            var builder = SdkTracerProvider.builder()
                    .setSpanLimits(limits)
                    .setSampler(sampler)
                    .addSpanProcessor(SimpleSpanProcessor.builder(exporter)
                            .setExportUnsampledSpans(true)
                            .build());
            var config = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            var factory = ExecutionOtelPlugin.factory(builder, config);
            var plugin = Invocations.started(
                    factory, new InvocationInfo("request", "arn:diagnostic", true, Instant.ofEpochSecond(1)));
            plugin.onOperationEnd(new OperationEndInfo(
                    "op",
                    NAME,
                    "CALLBACK",
                    "Callback",
                    null,
                    Instant.ofEpochSecond(10),
                    Instant.ofEpochSecond(20),
                    "FAILED",
                    null,
                    true,
                    error,
                    null));
            plugin.onInvocationEnd(
                    new InvocationEndInfo("request", "arn:diagnostic", true, InvocationStatus.SUCCEEDED, null));
            return List.copyOf(exporter.getFinishedSpanItems());
        }
    }
}
