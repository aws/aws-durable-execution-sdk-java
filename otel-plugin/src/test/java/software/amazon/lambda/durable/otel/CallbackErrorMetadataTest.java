// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class CallbackErrorMetadataTest {
    private static final String CALLBACK = "external-failure-callback";

    static Stream<Arguments> errors() {
        return Stream.of(false, true)
                .flatMap(executionView -> Stream.of(
                        Arguments.of(executionView, "omitted", null, false, false),
                        Arguments.of(
                                executionView, "empty", ErrorObject.builder().build(), false, false),
                        Arguments.of(executionView, "timeout", null, false, true),
                        Arguments.of(
                                executionView,
                                "type",
                                ErrorObject.builder()
                                        .errorType("ExternalFailure")
                                        .build(),
                                true,
                                false),
                        Arguments.of(
                                executionView,
                                "empty-type",
                                ErrorObject.builder().errorType("").build(),
                                true,
                                false),
                        Arguments.of(
                                executionView,
                                "message",
                                ErrorObject.builder()
                                        .errorMessage("failed externally")
                                        .build(),
                                true,
                                false),
                        Arguments.of(
                                executionView,
                                "empty-message",
                                ErrorObject.builder().errorMessage("").build(),
                                true,
                                false),
                        Arguments.of(
                                executionView,
                                "data",
                                ErrorObject.builder().errorData("{}").build(),
                                true,
                                false),
                        Arguments.of(
                                executionView,
                                "empty-data",
                                ErrorObject.builder().errorData("").build(),
                                true,
                                false),
                        Arguments.of(
                                executionView,
                                "empty-stack",
                                ErrorObject.builder().stackTrace(List.of()).build(),
                                true,
                                false),
                        Arguments.of(
                                executionView,
                                "stack",
                                ErrorObject.builder()
                                        .stackTrace("example.Service|call|Service.java|1")
                                        .build(),
                                true,
                                false)));
    }

    @ParameterizedTest(name = "executionView={0}, error={1}")
    @MethodSource("errors")
    void publicCallbackPreservesStoredFailureButReportsOnlyPresentErrorDetails(
            boolean executionView, String label, ErrorObject error, boolean hasDetails, boolean timeout) {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        try {
            var exporter = InMemorySpanExporter.create();
            var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var options = OtelPluginConfig.builder()
                    .contextExtractor(() -> null)
                    .enableMdc(false)
                    .build();
            DurableExecutionPlugin otel = executionView
                    ? new ExecutionOtelPlugin(provider, options)
                    : new InvocationOtelPlugin(provider, options);
            var starts = new CopyOnWriteArrayList<InvocationInfo>();
            var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
            var operationEnds = new CopyOnWriteArrayList<OperationEndInfo>();
            var observer = new DurableExecutionPlugin() {
                @Override
                public void onInvocationStart(InvocationInfo info) {
                    starts.add(info);
                }

                @Override
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.add(info);
                }

                @Override
                public void onOperationEnd(OperationEndInfo info) {
                    operationEnds.add(info);
                }
            };
            var submitterCalls = new AtomicInteger();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> context.waitForCallback(
                            "external-failure", String.class, (callbackId, step) -> submitterCalls.incrementAndGet()),
                    DurableConfig.builder().withPlugins(otel, observer).build());
            assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            var callbackId = runner.getCallbackId(CALLBACK);
            if (timeout) runner.timeoutCallback(callbackId);
            else runner.failCallback(callbackId, error);

            var result = runner.run("input");
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            var stored = result.getOperation(CALLBACK);
            var terminal = timeout ? "TIMED_OUT" : "FAILED";
            assertEquals(terminal, stored.getStatus().toString());
            assertEquals(error, stored.getCallbackDetails().error(), "raw checkpoint error must not be normalized");
            assertEquals(error, result.getError().orElse(null), "caller failure payload must remain unchanged");
            var callerError = assertInstanceOf(
                    DurableOperationException.class, ends.get(1).executionError());
            assertEquals(
                    timeout ? "CallbackTimeoutException" : "CallbackFailedException",
                    callerError.getClass().getSimpleName());
            assertEquals(error, callerError.getErrorObject());
            assertEquals(
                    hasDetails, starts.get(1).operations().get(stored.getId()).error() != null);
            assertEquals(
                    hasDetails,
                    starts.get(1).updatedOperations().get(stored.getId()).error() != null);
            assertEquals(
                    hasDetails, ends.get(1).operations().get(stored.getId()).error() != null);
            var callbackEnds = operationEnds.stream()
                    .filter(info -> CALLBACK.equals(info.name()))
                    .toList();
            assertEquals(1, callbackEnds.size());
            assertEquals(terminal, callbackEnds.get(0).status());
            assertEquals(hasDetails, callbackEnds.get(0).error() != null);
            var terminalSpans = exporter.getFinishedSpanItems().stream()
                    .filter(span -> CALLBACK.equals(span.getAttributes().get(DURABLE_OPERATION_NAME)))
                    .filter(span -> terminal.equals(span.getAttributes().get(DURABLE_OPERATION_STATUS)))
                    .toList();
            assertEquals(1, terminalSpans.size());
            assertEquals(
                    hasDetails ? StatusCode.ERROR : StatusCode.UNSET,
                    terminalSpans.get(0).getStatus().getStatusCode());
            assertEquals(hasDetails ? 1 : 0, terminalSpans.get(0).getEvents().size());
            var replay = runner.run("input");
            assertEquals(ExecutionStatus.FAILED, replay.getStatus());
            assertEquals(result.getError(), replay.getError());
            assertEquals(1, submitterCalls.get(), "completed submitter body must not repeat");
            assertEquals(
                    1,
                    operationEnds.stream()
                            .filter(info -> CALLBACK.equals(info.name()))
                            .count());
        } finally {
            DeterministicIdGenerator.clearSharedStateForTest();
            DurableSamplingDecision.clearSharedStateForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
        }
    }
}
