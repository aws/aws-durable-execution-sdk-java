// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;

class RuntimeHeaderFailureIntegrationTest {
    @Test
    void noPluginsDoesNotReadRuntimeTraceHeader() {
        executeWithThrowingRuntimeOverride(null, new AssertionError("must not read"));
    }

    @ParameterizedTest
    @CsvSource({
        "true,0,runtime",
        "true,0,assertion",
        "true,0,class-linkage",
        "true,0,method-linkage",
        "true,0,abstract-linkage",
        "true,1,runtime",
        "true,1,assertion",
        "true,1,class-linkage",
        "true,1,method-linkage",
        "true,1,abstract-linkage",
        "false,0,runtime",
        "false,0,assertion",
        "false,0,class-linkage",
        "false,0,method-linkage",
        "false,0,abstract-linkage",
        "false,1,runtime",
        "false,1,assertion",
        "false,1,class-linkage",
        "false,1,method-linkage",
        "false,1,abstract-linkage"
    })
    void throwingRuntimeOverrideCannotBorrowStaleGlobalCarrier(
            boolean executionView, String sampled, String failureKind) {
        var property = "com.amazonaws.xray.traceHeader";
        var previous = System.getProperty(property);
        var stale = "Root=1-6955b900-aaaaaaaaaaaaaaaaaaaaaaaa;Sampled=" + sampled;
        System.setProperty(property, stale);
        try {
            var exporter = InMemorySpanExporter.create();
            var builder = SdkTracerProvider.builder()
                    .setSampler(Sampler.alwaysOn())
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var config = OtelPluginConfig.builder().enableMdc(false).build();
            DurableExecutionPluginFactory plugin = executionView
                    ? ExecutionOtelPlugin.factory(builder, config)
                    : InvocationOtelPlugin.factory(builder, config);
            executeWithThrowingRuntimeOverride(plugin, accessorFailure(failureKind));
            var spans = exporter.getFinishedSpanItems();
            assertEquals(3, spans.size(), "a failed invocation accessor must not inherit stale sampling");
            // This factory branch already exports its synthetic ancestor in addition to Workflow and Invocation.
            assertEquals(
                    List.of("DurableExecutionRoot", "Invocation", "Workflow"),
                    spans.stream().map(span -> span.getName()).sorted().toList());
            assertTrue(
                    spans.stream().allMatch(span -> !"6955b900aaaaaaaaaaaaaaaaaaaaaaaa".equals(span.getTraceId())),
                    "a failed invocation accessor must not inherit another request's trace");
            assertEquals(stale, System.getProperty(property));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static void executeWithThrowingRuntimeOverride(DurableExecutionPluginFactory plugin, Throwable failure) {
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId()).thenAnswer(invocation -> {
            throw failure;
        });
        when(context.getRemainingTimeInMillis()).thenReturn(30000);
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/name/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
        var builder = DurableConfig.builder().withDurableExecutionClient(mock(DurableExecutionClient.class));
        if (plugin != null) builder.withPlugins(plugin);
        var config = builder.build();
        var result =
                DurableExecutor.execute(input, context, TypeToken.get(String.class), (value, ctx) -> value, config);
        assertEquals(ExecutionStatus.SUCCEEDED, result.status());
        assertEquals("\"input\"", result.result());
        if (plugin == null) verify(context, never()).getXrayTraceId();
    }

    private static Throwable accessorFailure(String kind) {
        return switch (kind) {
            case "runtime" -> new SecurityException("runtime carrier access denied");
            case "assertion" -> new AssertionError("optional runtime assertion");
            case "class-linkage" -> new NoClassDefFoundError("optional dependency");
            case "method-linkage" -> new NoSuchMethodError("inside available override");
            case "abstract-linkage" -> new AbstractMethodError("inside available override");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private abstract static class RuntimeContext implements Context {
        @Override
        public String getXrayTraceId() {
            return null;
        }
    }
}
