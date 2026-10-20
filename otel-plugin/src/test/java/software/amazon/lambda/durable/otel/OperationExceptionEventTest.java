// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;

class OperationExceptionEventTest {
    private static final String NAME = "backend-diagnostic";
    private static final AttributeKey<String> TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> STACK = AttributeKey.stringKey("exception.stacktrace");

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
            var plugin = new ExecutionOtelPlugin(builder, config);
            plugin.onInvocationStart(new InvocationInfo("request", "arn:diagnostic", true, Instant.ofEpochSecond(1)));
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
