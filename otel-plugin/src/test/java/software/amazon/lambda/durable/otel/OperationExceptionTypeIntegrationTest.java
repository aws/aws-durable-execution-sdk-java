// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestResult;

class OperationExceptionTypeIntegrationTest {
    private static final String RUNTIME_EXCEPTION = RuntimeException.class.getName();
    private InMemorySpanExporter exporter;

    @BeforeEach
    void setUp() {
        resetSharedState();
        exporter = InMemorySpanExporter.create();
    }

    @AfterEach
    void tearDown() {
        exporter.close();
        resetSharedState();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void childContextReportsStoredTypeAndSkipsCompletedCodeOnReplay(boolean executionView) {
        var calls = new AtomicInteger();
        var name = "otel-failed-child-context";
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.runInChildContext(name, String.class, child -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("Intentional child-context failure");
                }),
                config(executionView));

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var stored = result.getOperation(name).getContextDetails().error();
        assertEquals(RUNTIME_EXCEPTION, stored.errorType());
        assertLocalFailure(stored, result);
        assertExceptionType(name, RUNTIME_EXCEPTION);
        assertBoundaryExceptionTypes(RUNTIME_EXCEPTION);
        assertReplayPreservesFailure(runner, result, calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conditionReportsStoredTypeAndPreservesAttemptException(boolean executionView) {
        var calls = new AtomicInteger();
        var name = "otel-failed-condition";
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.waitForCondition(name, String.class, (state, step) -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("Intentional condition failure");
                }),
                config(executionView));

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var stored = result.getOperation(name).getStepDetails().error();
        assertEquals(RUNTIME_EXCEPTION, stored.errorType());
        assertLocalFailure(stored, result);
        assertExceptionType(name, RUNTIME_EXCEPTION);
        assertExceptionType(name + " attempt 1", RUNTIME_EXCEPTION);
        assertBoundaryExceptionTypes(RUNTIME_EXCEPTION);
        assertReplayPreservesFailure(runner, result, calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackReportsRemoteTypeAndPreservesBoundaryWrapper(boolean executionView) {
        var submitterCalls = new AtomicInteger();
        var name = "otel-failed-callback";
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.waitForCallback(
                        name, String.class, (callbackId, step) -> submitterCalls.incrementAndGet()),
                config(executionView));
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var stored = remoteError("RejectedError");
        runner.failCallback(runner.getCallbackId(name + "-callback"), stored);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(
                stored,
                result.getOperation(name + "-callback").getCallbackDetails().error());
        assertEquals(stored, result.getOperation(name).getContextDetails().error());
        assertEquals(stored, result.getError().orElseThrow());
        assertExceptionType(name, "RejectedError");
        assertExceptionType(name + "-callback", "RejectedError");
        assertBoundaryExceptionTypes(CallbackFailedException.class.getName());
        assertReplayPreservesFailure(runner, result, submitterCalls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invokeReportsStoredTypeAndPreservesBoundaryWrapper(boolean executionView) {
        var stepCalls = new AtomicInteger();
        var name = "otel-failed-invoke";
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("before-invoke", Integer.class, step -> stepCalls.incrementAndGet());
                    return context.invoke(name, "target-fn", "{}", String.class);
                },
                config(executionView));
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var stored = remoteError(RUNTIME_EXCEPTION);
        runner.failChainedInvoke(name, stored);

        var result = runner.runUntilComplete("input");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(stored, result.getOperation(name).getChainedInvokeDetails().error());
        assertEquals(stored, result.getError().orElseThrow());
        assertExceptionType(name, RUNTIME_EXCEPTION);
        assertBoundaryExceptionTypes(InvokeFailedException.class.getName());
        assertReplayPreservesFailure(runner, result, stepCalls);
    }

    private DurableConfig config(boolean executionView) {
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var settings = OtelPluginConfig.builder()
                .contextExtractor(() -> null)
                .enableMdc(false)
                .build();
        DurableExecutionPluginFactory factory = executionView
                ? ExecutionOtelPlugin.factory(builder, settings)
                : InvocationOtelPlugin.factory(builder, settings);
        return DurableConfig.builder().withPlugins(factory).build();
    }

    private void assertExceptionType(String name, String expectedType) {
        var failedSpans = exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .filter(span -> span.getStatus().getStatusCode() == StatusCode.ERROR)
                .toList();
        assertEquals(1, failedSpans.size(), name);
        var events = failedSpans.get(0).getEvents();
        assertEquals(1, events.size(), name);
        assertEquals("exception", events.get(0).getName());
        assertEquals(expectedType, events.get(0).getAttributes().get(AttributeKey.stringKey("exception.type")));
    }

    private void assertBoundaryExceptionTypes(String expectedType) {
        assertExceptionType("Workflow", expectedType);
        assertExceptionType("Invocation", expectedType);
    }

    private static void assertLocalFailure(ErrorObject stored, TestResult<String> result) {
        var failure = result.getError().orElseThrow();
        assertEquals(stored.errorType(), failure.errorType());
        assertEquals(stored.errorMessage(), failure.errorMessage());
        assertEquals(stored.stackTrace(), failure.stackTrace());
    }

    private void assertReplayPreservesFailure(
            LocalDurableTestRunner<String, String> runner, TestResult<String> result, AtomicInteger calls) {
        exporter.reset();
        var replay = runner.run("input");
        assertEquals(ExecutionStatus.FAILED, replay.getStatus());
        assertEquals(result.getError(), replay.getError());
        assertEquals(1, calls.get(), "completed user code must not run again");
        // Without a remote parent, each invocation also re-exports the deterministic execution root.
        assertEquals(
                List.of("DurableExecutionRoot", "Invocation", "Workflow"),
                exporter.getFinishedSpanItems().stream()
                        .map(SpanData::getName)
                        .sorted()
                        .toList(),
                "replay must not emit operation or attempt spans");
    }

    private static ErrorObject remoteError(String type) {
        return ErrorObject.builder()
                .errorType(type)
                .errorMessage("Intentional remote failure")
                .stackTrace("Target|handle|Target.java|42")
                .build();
    }

    private static void resetSharedState() {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }
}
