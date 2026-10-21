// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.DURABLE_OPERATION_NAME;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.OperationEndInfo;

@Timeout(15)
class BackendOperationTimingTest {
    private static final String NAME = "backend-timed-callback";
    private static final Instant END = Instant.ofEpochSecond(20);

    static Stream<Arguments> paths() {
        return Stream.of(false, true)
                .flatMap(execution ->
                        Stream.of("live", "continuation", "missing-start").map(path -> Arguments.of(execution, path)));
    }

    @ParameterizedTest
    @MethodSource("paths")
    void publicHandlerUsesBackendEndAndCompletedReplaySkipsHooks(boolean executionView, String path) {
        boolean live = path.equals("live");
        var details = ErrorObject.builder()
                .errorType("ExternalFailure")
                .errorMessage("backend failure")
                .build();
        var callback = Operation.builder()
                .id(OperationIdGenerator.hashOperationId("1"))
                .name(NAME)
                .type(OperationType.CALLBACK)
                .subType("Callback")
                .status(OperationStatus.FAILED)
                .startTimestamp(path.equals("missing-start") ? null : Instant.ofEpochSecond(10))
                .endTimestamp(END)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .error(details)
                        .build())
                .build();
        var execution = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.ofEpochSecond(1))
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        var calls = new AtomicInteger();
        var client = new DurableExecutionClient() {
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                calls.incrementAndGet();
                assertEquals(1, updates.size());
                assertEquals(callback.id(), updates.get(0).id());
                assertEquals(OperationAction.START, updates.get(0).action());
                return CheckpointDurableExecutionResponse.builder()
                        .checkpointToken("next")
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(callback)
                                .build())
                        .build();
            }

            public GetDurableExecutionStateResponse getExecutionState(String arn, String token, String marker) {
                fail("complete unpaginated state must not require another backend read");
                return null;
            }
        };
        try (var exporter = InMemorySpanExporter.create()) {
            var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var settings = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            DurableExecutionPluginFactory otel = executionView
                    ? ExecutionOtelPlugin.factory(builder, settings)
                    : InvocationOtelPlugin.factory(builder, settings);
            var ends = new CopyOnWriteArrayList<OperationEndInfo>();
            var observer = new DurableExecutionPlugin() {
                public void onOperationEnd(OperationEndInfo info) {
                    if (NAME.equals(info.name())) ends.add(info);
                }
            };
            var caught = new AtomicInteger();
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(client)
                    .withPlugins(otel, ignored -> observer)
                    .build();
            for (int invocation = 0; invocation < 2; invocation++) {
                var firstLive = live && invocation == 0;
                var input = new DurableExecutionInput(
                        "arn:timing/execution",
                        "token",
                        CheckpointUpdatedExecutionState.builder()
                                .operations(firstLive ? List.of(execution) : List.of(execution, callback))
                                .build(),
                        !live && invocation == 0 ? List.of(callback.id()) : List.of());
                var result = DurableExecutor.execute(
                        input,
                        null,
                        TypeToken.get(String.class),
                        (value, context) -> {
                            try {
                                context.createCallback(NAME, String.class).get();
                                fail("backend failure must still reach the handler");
                            } catch (DurableOperationException failure) {
                                assertEquals(details, failure.getErrorObject());
                                caught.incrementAndGet();
                            }
                            return "handled";
                        },
                        config);
                assertEquals(ExecutionStatus.SUCCEEDED, result.status());
                assertEquals("\"handled\"", result.result());
                assertNull(result.error());
            }
            assertEquals(2, caught.get());
            assertEquals(live ? 1 : 0, calls.get());
            assertEquals(1, ends.size(), "completed replay must not re-emit the terminal hook");
            assertEquals(END, ends.get(0).endTimestamp());
            assertEquals(!live, ends.get(0).isReplay());
            var spans = exporter.getFinishedSpanItems().stream()
                    .filter(s -> NAME.equals(s.getAttributes().get(DURABLE_OPERATION_NAME)))
                    .toList();
            assertEquals(1, spans.size());
            var span = spans.get(0);
            assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
            assertEquals(1, span.getEvents().size());
            var diagnostic = span.getEvents().get(0);
            var failure = ends.get(0).error();
            assertEquals(
                    failure.getClass().getCanonicalName(),
                    diagnostic.getAttributes().get(AttributeKey.stringKey("exception.type")));
            assertEquals(
                    failure.getMessage(), diagnostic.getAttributes().get(AttributeKey.stringKey("exception.message")));
            assertTrue(diagnostic
                    .getAttributes()
                    .get(AttributeKey.stringKey("exception.stacktrace"))
                    .contains("backend failure"));
            long event = diagnostic.getEpochNanos();
            System.out.printf(
                    "PUBLIC_BACKEND_TIME execution=%s path=%s backendEnd=%s start=%s end=%s event=%s caught=%s hooks=%s calls=%s%n",
                    executionView,
                    path,
                    END,
                    span.getStartEpochNanos(),
                    span.getEndEpochNanos(),
                    event,
                    caught,
                    ends.size(),
                    calls);
            if (executionView) {
                assertEquals(20_000_000_000L, span.getEndEpochNanos());
                assertEquals(
                        path.equals("missing-start") ? 20_000_000_000L : 10_000_000_000L, span.getStartEpochNanos());
                assertEquals(span.getEndEpochNanos(), event, "exception must use the same backend end");
            } else assertTrue(span.getStartEpochNanos() <= event && event <= span.getEndEpochNanos());
        }
    }
}
