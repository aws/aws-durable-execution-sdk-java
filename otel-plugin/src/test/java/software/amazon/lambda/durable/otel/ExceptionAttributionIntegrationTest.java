// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

@Timeout(20)
class ExceptionAttributionIntegrationTest {
    private static final String NAME = "failed-operation";
    private static final String MESSAGE = "Intentional failure";
    private static final AttributeKey<String> TYPE = AttributeKey.stringKey("exception.type");

    @AfterEach
    void clearTracingState() {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(false, true)
                .flatMap(execution -> Stream.of("child", "condition", "callback", "invoke", "handler")
                        .map(scenario -> Arguments.of(execution, scenario)));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void failuresRecordOriginalTypeOnlyOnOperationsAndAttempts(boolean executionView, String scenario) {
        try (var exporter = InMemorySpanExporter.create()) {
            var calls = new AtomicInteger();
            var runner = LocalDurableTestRunner.create(
                    String.class, (input, context) -> fail(context, scenario, calls), config(executionView, exporter));
            completeBackendFailure(runner, scenario);
            var result = runner.run("input");
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            assertAttribution(exporter.getFinishedSpanItems(), scenario);
            var replay = runner.run("input");
            assertEquals(ExecutionStatus.FAILED, replay.getStatus());
            assertEquals(result.getError(), replay.getError());
            if (!scenario.equals("handler")) assertEquals(scenario.equals("invoke") ? 0 : 1, calls.get());
        }
    }

    private static void completeBackendFailure(LocalDurableTestRunner<String, String> runner, String scenario) {
        if (!scenario.equals("callback") && !scenario.equals("invoke")) return;
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var details = ErrorObject.builder()
                .errorType(scenario.equals("callback") ? "RejectedError" : RuntimeException.class.getName())
                .errorMessage(MESSAGE)
                .build();
        if (scenario.equals("callback")) runner.failCallback(runner.getCallbackId(NAME + "-callback"), details);
        else runner.failChainedInvoke(NAME, details);
    }

    private static String fail(DurableContext context, String scenario, AtomicInteger calls) {
        return switch (scenario) {
            case "child" -> context.runInChildContext(NAME, String.class, child -> throwFailure(calls));
            case "condition" -> context.waitForCondition(NAME, String.class, (state, step) -> throwFailure(calls));
            case "callback" -> context.waitForCallback(NAME, String.class, (id, step) -> calls.incrementAndGet());
            case "invoke" -> context.invoke(NAME, "target-function", "input", String.class);
            case "handler" -> throwFailure(calls);
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    private static <T> T throwFailure(AtomicInteger calls) {
        calls.incrementAndGet();
        throw new RuntimeException(MESSAGE);
    }

    private static DurableConfig config(boolean execution, InMemorySpanExporter exporter) {
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var options = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> null)
                .build();
        DurableExecutionPluginFactory factory = execution
                ? ExecutionOtelPlugin.factory(provider, options)
                : InvocationOtelPlugin.factory(provider, options);
        return DurableConfig.builder().withPlugins(factory).build();
    }

    private static void assertAttribution(List<SpanData> spans, String scenario) {
        var failedOperations = spans.stream()
                .filter(span -> span.getAttributes().get(DURABLE_OPERATION_ID) != null)
                .filter(span -> span.getStatus().getStatusCode() == StatusCode.ERROR)
                .toList();
        int expected = switch (scenario) {
            case "handler" -> 0;
            case "condition", "callback" -> 2;
            default -> 1;
        };
        assertEquals(expected, failedOperations.size());
        for (var operation : failedOperations) {
            assertEquals(1, operation.getEvents().size(), operation.getName());
            var event = operation.getEvents().get(0);
            assertEquals("exception", event.getName());
            assertEquals(
                    scenario.equals("callback") ? "RejectedError" : RuntimeException.class.getName(),
                    event.getAttributes().get(TYPE),
                    operation.getName());
            assertEquals(MESSAGE, event.getAttributes().get(AttributeKey.stringKey("exception.message")));
        }
        assertRootStatus(spans);
    }

    private static void assertRootStatus(List<SpanData> spans) {
        var failedRoots = spans.stream()
                .filter(span ->
                        span.getName().equals("Workflow") || span.getName().equals("Invocation"))
                .filter(span -> span.getStatus().getStatusCode() == StatusCode.ERROR)
                .toList();
        assertEquals(2, failedRoots.size());
        for (var span : failedRoots) {
            assertTrue(span.getStatus().getDescription().contains(MESSAGE), span.getName());
            assertTrue(span.getEvents().isEmpty(), span.getName());
        }
    }
}
