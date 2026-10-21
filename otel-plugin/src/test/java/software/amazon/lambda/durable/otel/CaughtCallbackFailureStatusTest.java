// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

@Timeout(15)
class CaughtCallbackFailureStatusTest {
    private static final String NAME = "caught-failure-callback";

    static Stream<Arguments> cases() {
        return Stream.of(false, true)
                .flatMap(execution -> Stream.of(false, true)
                        .flatMap(live -> Stream.of("omitted", "empty", "timeout", "rich")
                                .map(kind -> Arguments.of(execution, live, kind))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void caughtCallbackFailureIsErrorWhileHandlerSucceedsAndReplaySkipsSavedEffects(
            boolean executionView, boolean live, String kind) {
        ErrorObject details = kind.equals("empty")
                ? ErrorObject.builder().build()
                : kind.equals("rich")
                        ? ErrorObject.builder()
                                .errorType("ExternalFailure")
                                .errorMessage("backend failure")
                                .build()
                        : null;
        var status = kind.equals("timeout") ? "TIMED_OUT" : "FAILED";
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
                @Override
                public void onOperationEnd(OperationEndInfo info) {
                    if (NAME.equals(info.name())) ends.add(info);
                }
            };
            var runnerRef = new AtomicReference<LocalDurableTestRunner<String, String>>();
            var submitted = new AtomicInteger();
            var saved = new AtomicInteger();
            var caught = new CopyOnWriteArrayList<DurableOperationException>();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (String input, DurableContext context) -> {
                        try {
                            context.waitForCallback("caught-failure", String.class, (callbackId, step) -> {
                                submitted.incrementAndGet();
                                if (live) finish(runnerRef.get(), callbackId, kind, details);
                            });
                            fail("failed callback must still throw into the handler");
                        } catch (DurableOperationException failure) {
                            caught.add(failure);
                        }
                        context.step("saved-effect", String.class, step -> {
                            saved.incrementAndGet();
                            return "saved";
                        });
                        context.wait("resume-once", Duration.ofSeconds(1));
                        return "handled";
                    },
                    DurableConfig.builder()
                            .withPlugins(otel, ignored -> observer)
                            .build());
            runnerRef.set(runner);
            assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            if (!live) {
                assertTrue(caught.isEmpty());
                finish(runner, runner.getCallbackId(NAME), kind, details);
                assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            }
            assertEquals(1, caught.size());
            assertEquals(1, ends.size());
            assertEquals(status, ends.get(0).status());
            assertEquals(details != null, ends.get(0).error() != null);
            var firstTerminal = terminal(exporter, status);
            assertEquals(1, firstTerminal.size());
            assertEquals(StatusCode.ERROR, firstTerminal.get(0).getStatus().getStatusCode());
            if (kind.equals("rich")) {
                var span = firstTerminal.get(0);
                assertEquals(1, span.getEvents().size());
                var eventTime = span.getEvents().get(0).getEpochNanos();
                var backendEnd = ends.get(0).endTimestamp();
                System.out.printf(
                        "PUBLIC_OPERATION_TIME execution=%s live=%s backendEnd=%s start=%s end=%s event=%s%n",
                        executionView, live, backendEnd, span.getStartEpochNanos(), span.getEndEpochNanos(), eventTime);
                if (executionView && backendEnd != null) assertEquals(span.getEndEpochNanos(), eventTime);
                else assertTrue(eventTime >= span.getStartEpochNanos() && eventTime <= span.getEndEpochNanos());
            } else assertTrue(firstTerminal.get(0).getEvents().isEmpty());
            runner.advanceTime();
            var result = runner.runUntilComplete("input");
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals("handled", result.getResult(String.class));
            assertEquals(details, result.getOperation(NAME).getCallbackDetails().error());
            assertTrue(result.getError().isEmpty(), "caught operation failure is not handler failure");
            assertEquals(2, caught.size());
            caught.forEach(failure -> assertEquals(details, failure.getErrorObject()));
            assertEquals(1, submitted.get());
            assertEquals(1, saved.get());
            assertEquals(1, ends.size());
            assertEquals(1, terminal(exporter, status).size(), "completed callback end is not emitted twice");
            var workflow = exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals("Workflow"))
                    .toList();
            assertEquals(1, workflow.size());
            assertEquals(StatusCode.OK, workflow.get(0).getStatus().getStatusCode());
        }
    }

    private static void finish(
            LocalDurableTestRunner<String, String> runner, String id, String kind, ErrorObject details) {
        if (kind.equals("timeout")) runner.timeoutCallback(id);
        else runner.failCallback(id, details);
    }

    private static List<SpanData> terminal(InMemorySpanExporter exporter, String status) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> NAME.equals(s.getAttributes().get(DURABLE_OPERATION_NAME)))
                .filter(s -> status.equals(s.getAttributes().get(DURABLE_OPERATION_STATUS)))
                .toList();
    }
}
