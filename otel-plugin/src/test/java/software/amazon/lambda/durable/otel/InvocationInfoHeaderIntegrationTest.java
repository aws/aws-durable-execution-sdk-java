// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class InvocationInfoHeaderIntegrationTest {
    private static final String TRACE = "6955b900123456789012345678901234";
    private static final String PARENT = "1234567890123456";
    private static final String HEADER = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1";
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/name/id";

    @ParameterizedTest
    @CsvSource({
        "factory,unreadable-cause",
        "hook,unreadable-cause",
        "factory,present",
        "hook,present",
        "factory,empty",
        "hook,empty",
        "factory,null",
        "hook,null",
        "factory,unavailable",
        "hook,unavailable"
    })
    void ordinaryFactoryAndHookCanReadTheCapturedHeader(String reader, String carrier) {
        var runtimeThread = Thread.currentThread();
        var capturedThread = new AtomicReference<Thread>();
        var factoryInfo = new AtomicReference<InvocationInfo>();
        var hookInfo = new AtomicReference<InvocationInfo>();
        var observed = new AtomicReference<>("callback did not run");
        var runtime = "unavailable".equals(carrier) ? null : mock(RuntimeContext.class);
        if (runtime != null) {
            when(runtime.getRemainingTimeInMillis()).thenReturn(30000);
            when(runtime.getXrayTraceId()).thenAnswer(ignored -> {
                capturedThread.set(Thread.currentThread());
                return switch (carrier) {
                    case "unreadable-cause" ->
                        throw new CompletionException("unreadable cause", null) {
                            @Override
                            public synchronized Throwable getCause() {
                                throw new IllegalStateException("cause accessor failed");
                            }
                        };
                    case "present" -> HEADER;
                    case "empty" -> "";
                    default -> null;
                };
            });
        }
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .withPlugins(info -> {
                    factoryInfo.set(info);
                    if (reader.equals("factory")) observed.set(info.xRayTraceId());
                    return new DurableExecutionPlugin() {
                        @Override
                        public void onInvocationStart(InvocationInfo info) {
                            hookInfo.set(info);
                            if (reader.equals("hook")) observed.set(info.xRayTraceId());
                        }
                    };
                })
                .build();
        var output =
                DurableExecutor.execute(input(), runtime, TypeToken.get(String.class), (value, ctx) -> value, config);
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("\"input\"", output.result());
        assertNotNull(factoryInfo.get());
        assertSame(factoryInfo.get(), hookInfo.get(), "Factory and hook must receive the same immutable snapshot");
        var expected =
                switch (carrier) {
                    case "present" -> HEADER;
                    case "unavailable" -> null;
                    default -> "";
                };
        assertEquals(expected, observed.get(), reader + " must read the captured value from InvocationInfo");
        if (runtime != null) {
            verify(runtime, times(1)).getXrayTraceId();
            assertSame(runtimeThread, capturedThread.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directFactoryDispatchesTheInvocationAwareCustomExtractor(boolean executionView) {
        var seen = new AtomicReference<InvocationInfo>();
        var exporter = InMemorySpanExporter.create();
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(new ContextExtractor() {
                    @Override
                    public ExtractedContext extract() {
                        return null;
                    }

                    @Override
                    public ExtractedContext extract(InvocationInfo info) {
                        seen.set(info);
                        assertEquals(HEADER, info.xRayTraceId());
                        return new ExtractedContext(TRACE, PARENT, ExtractedContext.Sampling.SAMPLED);
                    }
                })
                .build();
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var factory = executionView
                ? ExecutionOtelPlugin.factory(provider, config)
                : InvocationOtelPlugin.factory(provider, config);
        var info = new InvocationInfo("request", ARN, true, Instant.EPOCH, null, Map.of(), Map.of(), HEADER);
        var plugin = factory.createPlugin(info);
        plugin.onInvocationStart(info);
        plugin.onInvocationEnd(new InvocationEndInfo("request", ARN, true, InvocationStatus.SUCCEEDED, null));
        assertSame(info, seen.get(), "The extract(info) extension must run before factory span creation");
        var spans = exporter.getFinishedSpanItems();
        assertEquals(2, spans.size(), "A complete remote parent is external, not a synthetic root");
        assertTrue(spans.stream().allMatch(span -> TRACE.equals(span.getTraceId())));
        assertTrue(spans.stream().allMatch(span -> PARENT.equals(span.getParentSpanId())));
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                ARN,
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    private abstract static class RuntimeContext implements Context {
        @Override
        public String getXrayTraceId() {
            return null;
        }
    }
}
