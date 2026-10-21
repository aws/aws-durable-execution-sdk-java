// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;

class TerminalOperationStatusTest {
    static Stream<Arguments> failures() {
        return Stream.of(false, true)
                .flatMap(execution -> Stream.of(false, true)
                        .flatMap(start -> Stream.of("FAILED", "TIMED_OUT", "STOPPED")
                                .flatMap(status -> Stream.of(false, true)
                                        .map(empty -> Arguments.of(execution, start, status, empty)))));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void explicitTerminalFailureIsErrorWithoutInventedExceptionDetails(
            boolean executionView, boolean matchingStart, String status, boolean emptyContainer) {
        var operation = Operation.builder()
                .id("op")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.fromValue(status))
                .build();
        Throwable error = emptyContainer
                ? new DurableOperationException(operation, ErrorObject.builder().build())
                : null;
        check(executionView, matchingStart, status, error, StatusCode.ERROR);
    }

    static Stream<Arguments> controls() {
        return Stream.of(false, true)
                .flatMap(execution -> Stream.of("SUCCEEDED", "PENDING", "RETRYING", "CANCELLED")
                        .map(status -> Arguments.of(execution, status)));
    }

    @ParameterizedTest
    @MethodSource("controls")
    void successAndNonfailureStatusesKeepTheirExistingMeaning(boolean executionView, String status) {
        check(executionView, true, status, null, status.equals("SUCCEEDED") ? StatusCode.OK : StatusCode.UNSET);
    }

    private static void check(
            boolean executionView, boolean matchingStart, String status, Throwable error, StatusCode expected) {
        try (var exporter = InMemorySpanExporter.create()) {
            var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var settings = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            DurableExecutionPluginFactory factory = executionView
                    ? ExecutionOtelPlugin.factory(builder, settings)
                    : InvocationOtelPlugin.factory(builder, settings);
            var start = Instant.parse("2026-10-14T00:00:00Z");
            var plugin =
                    Invocations.started(factory, new InvocationInfo("request", "arn:status", !matchingStart, start));
            if (matchingStart)
                plugin.onOperationStart(new OperationInfo(
                        "op", "status-target", "CALLBACK", "Callback", null, start, null, null, false));
            plugin.onOperationEnd(new OperationEndInfo(
                    "op",
                    "status-target",
                    "CALLBACK",
                    "Callback",
                    null,
                    start,
                    start.plusSeconds(1),
                    status,
                    null,
                    !matchingStart,
                    error,
                    null));
            plugin.onInvocationEnd(
                    new InvocationEndInfo("request", "arn:status", true, InvocationStatus.SUCCEEDED, null));
            var spans = exporter.getFinishedSpanItems().stream()
                    .filter(s -> "status-target".equals(s.getAttributes().get(DURABLE_OPERATION_NAME)))
                    .toList();
            assertEquals(1, spans.size());
            var span = spans.get(0);
            assertEquals(status, span.getAttributes().get(DURABLE_OPERATION_STATUS));
            assertEquals(expected, span.getStatus().getStatusCode());
            assertEquals("", span.getStatus().getDescription(), "missing details do not become synthetic text");
            assertTrue(span.getEvents().isEmpty(), "filtered/absent details do not become an exception event");
        }
    }
}
