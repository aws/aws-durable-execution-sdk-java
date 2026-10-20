// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_OPERATION_NAME;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class VirtualOperationErrorStatusTest {
    private static final String NAME = "virtual-child";

    @ParameterizedTest
    @CsvSource({
        "false,empty", "true,empty",
        "false,without-container", "true,without-container",
        "false,ordinary", "true,ordinary",
        "false,explicit-message", "true,explicit-message",
        "false,empty-message", "true,empty-message",
        "false,explicit-cause", "true,explicit-cause",
        "false,explicit-suppressed", "true,explicit-suppressed",
        "false,explicit-stack", "true,explicit-stack",
        "false,success", "true,success"
    })
    void publicVirtualChildKeepsFailureStatusAcrossReplay(boolean executionView, String outcome) {
        try (var exporter = InMemorySpanExporter.create()) {
            var plugin = plugin(executionView, exporter);
            var ends = new CopyOnWriteArrayList<OperationEndInfo>();
            var listener = new DurableExecutionPlugin() {
                public void onOperationEnd(OperationEndInfo info) {
                    if (NAME.equals(info.name())) ends.add(info);
                }
            };
            var failure = failure(outcome);
            var sideEffects = new AtomicInteger();
            var callerFailures = new CopyOnWriteArrayList<RuntimeException>();
            var childResults = new CopyOnWriteArrayList<String>();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        try {
                            childResults.add(context.runInChildContext(
                                    NAME,
                                    String.class,
                                    child -> {
                                        if (failure != null) throw failure;
                                        return "child result";
                                    },
                                    RunInChildContextConfig.builder()
                                            .isVirtual(true)
                                            .build()));
                        } catch (RuntimeException caught) {
                            callerFailures.add(caught);
                        }
                        context.step("once", String.class, step -> {
                            sideEffects.incrementAndGet();
                            return "saved";
                        });
                        context.wait("pause", Duration.ofSeconds(1));
                        return "handled";
                    },
                    DurableConfig.builder()
                            .withPlugins(plugin, ignored -> listener)
                            .build());
            assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());
            assertTrue(ends.size() >= 2, "The virtual body runs on initial invocation and replay");
            if (failure == null) {
                assertTrue(callerFailures.isEmpty());
                assertEquals(ends.size(), childResults.size());
                assertTrue(childResults.stream().allMatch("child result"::equals));
            } else {
                assertTrue(childResults.isEmpty());
                assertEquals(ends.size(), callerFailures.size());
                callerFailures.forEach(caught -> {
                    if (failure instanceof DurableOperationException original) {
                        var translated = assertInstanceOf(DurableOperationException.class, caught);
                        assertEquals(original.getErrorObject(), translated.getErrorObject());
                    } else {
                        assertEquals(failure.getClass(), caught.getClass());
                        assertEquals(failure.getMessage(), caught.getMessage());
                    }
                });
            }

            ends.forEach(info -> {
                assertNull(info.status());
                assertSame(failure, info.error());
            });
            var spans = exporter.getFinishedSpanItems().stream()
                    .filter(span -> NAME.equals(span.getAttributes().get(DURABLE_OPERATION_NAME)))
                    .toList();
            assertEquals(ends.size(), spans.size());
            spans.forEach(span -> {
                assertEquals(status(outcome), span.getStatus().getStatusCode());
                assertEquals(
                        hasExceptionEvent(outcome) ? 1 : 0, span.getEvents().size());
                if (hasExceptionEvent(outcome))
                    assertDiagnostics(span.getEvents().get(0), failure, outcome);
            });
            assertEquals(1, sideEffects.get(), "Completed durable work is skipped on replay");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "empty",
                "ordinary",
                "success",
                "explicit-message",
                "empty-message",
                "explicit-cause",
                "explicit-suppressed",
                "explicit-stack"
            })
    void missingStartInvocationSegmentRetainsVirtualFailure(String outcome) {
        try (var exporter = InMemorySpanExporter.create()) {
            var factory = plugin(false, exporter);
            var plugin =
                    Invocations.started(factory, new InvocationInfo("request", "arn:virtual", true, Instant.EPOCH));
            plugin.onOperationEnd(new OperationEndInfo(
                    "virtual",
                    NAME,
                    "CONTEXT",
                    "RunInChildContext",
                    null,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    null,
                    null,
                    false,
                    failure(outcome),
                    null));
            plugin.onInvocationEnd(
                    new InvocationEndInfo("request", "arn:virtual", true, InvocationStatus.SUCCEEDED, null));
            var spans = exporter.getFinishedSpanItems().stream()
                    .filter(span -> NAME.equals(span.getAttributes().get(DURABLE_OPERATION_NAME)))
                    .toList();
            assertEquals(1, spans.size());
            assertEquals(status(outcome), spans.get(0).getStatus().getStatusCode());
            assertEquals(
                    hasExceptionEvent(outcome) ? 1 : 0, spans.get(0).getEvents().size());
            if (hasExceptionEvent(outcome))
                assertDiagnostics(spans.get(0).getEvents().get(0), failure(outcome), outcome);
        }
    }

    private static DurableExecutionPluginFactory plugin(boolean executionView, InMemorySpanExporter exporter) {
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> null)
                .build();
        return executionView
                ? ExecutionOtelPlugin.factory(provider, config)
                : InvocationOtelPlugin.factory(provider, config);
    }

    private static RuntimeException failure(String outcome) {
        var operation = Operation.builder()
                .id("external")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.FAILED)
                .build();
        return switch (outcome) {
            case "empty" ->
                new DurableOperationException(operation, ErrorObject.builder().build());
            case "without-container" -> new DurableOperationException(operation, null, "explicit failure");
            case "explicit-message" ->
                new DurableOperationException(operation, ErrorObject.builder().build(), "diagnostic-message");
            case "empty-message" ->
                new DurableOperationException(operation, ErrorObject.builder().build(), "");
            case "explicit-cause" ->
                new DurableOperationException(
                        operation,
                        ErrorObject.builder().build(),
                        null,
                        new IllegalArgumentException("diagnostic-cause"));
            case "explicit-suppressed" -> {
                var failure = new DurableOperationException(
                        operation, ErrorObject.builder().build());
                failure.addSuppressed(new IllegalStateException("diagnostic-suppressed"));
                yield failure;
            }
            case "explicit-stack" ->
                new DurableOperationException(
                        operation,
                        ErrorObject.builder().build(),
                        null,
                        new StackTraceElement[] {new StackTraceElement("example.Virtual", "fail", "Virtual.java", 7)},
                        null);
            case "ordinary" -> new IllegalStateException("ordinary failure");
            case "success" -> null;
            default -> throw new IllegalArgumentException(outcome);
        };
    }

    private static void assertDiagnostics(EventData event, RuntimeException failure, String outcome) {
        if (failure.getMessage() != null) {
            assertEquals(failure.getMessage(), event.getAttributes().get(AttributeKey.stringKey("exception.message")));
        }
        var stack = event.getAttributes().get(AttributeKey.stringKey("exception.stacktrace"));
        switch (outcome) {
            case "explicit-cause" -> assertTrue(stack.contains("diagnostic-cause"));
            case "explicit-suppressed" -> assertTrue(stack.contains("diagnostic-suppressed"));
            case "explicit-stack" -> assertTrue(stack.contains("example.Virtual.fail(Virtual.java:7)"));
            default -> {}
        }
    }

    private static boolean hasExceptionEvent(String outcome) {
        return !outcome.equals("empty") && !outcome.equals("success");
    }

    private static StatusCode status(String outcome) {
        return switch (outcome) {
            case "success" -> StatusCode.OK;
            default -> StatusCode.ERROR;
        };
    }
}
