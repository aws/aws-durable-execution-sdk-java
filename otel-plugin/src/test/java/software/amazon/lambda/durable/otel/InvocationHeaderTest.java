// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

class InvocationHeaderTest {
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
            DurableExecutionPlugin plugin = executionView
                    ? new ExecutionOtelPlugin(builder, config)
                    : new InvocationOtelPlugin(builder, config);
            executeWithThrowingRuntimeOverride(plugin, accessorFailure(failureKind));
            var spans = exporter.getFinishedSpanItems();
            assertEquals(2, spans.size(), "a failed invocation accessor must not inherit stale sampling");
            assertTrue(
                    spans.stream().allMatch(span -> !"6955b900aaaaaaaaaaaaaaaaaaaaaaaa".equals(span.getTraceId())),
                    "a failed invocation accessor must not inherit another request's trace");
            assertEquals(stale, System.getProperty(property));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static void executeWithThrowingRuntimeOverride(DurableExecutionPlugin plugin, Throwable failure) {
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
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .withPlugins(plugin)
                .build();
        var result =
                DurableExecutor.execute(input, context, TypeToken.get(String.class), (value, ctx) -> value, config);
        assertEquals(ExecutionStatus.SUCCEEDED, result.status());
        assertEquals("\"input\"", result.result());
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlappingInvocationsAndResumeKeepTheirOwnHeader(boolean executionView) throws Exception {
        var barrier = new CyclicBarrier(2);
        var sampled = CompletableFuture.runAsync(() -> runInvocations(executionView, true, barrier));
        var unsampled = CompletableFuture.runAsync(() -> runInvocations(executionView, false, barrier));
        CompletableFuture.allOf(sampled, unsampled).get(20, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invocationFieldPreservesLegacyPluginSubclassDispatch(boolean executionView) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder().enableMdc(false).build();
        var calls = new AtomicInteger();
        DurableExecutionPlugin plugin = executionView
                ? new ExecutionOtelPlugin(builder, config) {
                    @Override
                    public void onInvocationStart(InvocationInfo info) {
                        calls.incrementAndGet();
                        super.onInvocationStart(info);
                    }
                }
                : new InvocationOtelPlugin(builder, config) {
                    @Override
                    public void onInvocationStart(InvocationInfo info) {
                        calls.incrementAndGet();
                        super.onInvocationStart(info);
                    }
                };
        var info = new InvocationInfo("request", "arn", true, Instant.EPOCH);
        var runtime = ("Root=1-6955b900-123456789012345678901234;Sampled=0");
        plugin.onInvocationStart(withHeader(info, runtime));
        plugin.onInvocationEnd(new InvocationEndInfo("request", "arn", true, InvocationStatus.SUCCEEDED, null));
        assertEquals(1, calls.get());
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        plugin.onInvocationStart(info); // An ordinary direct call must not retain the previous invocation's header.
        plugin.onInvocationEnd(new InvocationEndInfo("request", "arn", true, InvocationStatus.SUCCEEDED, null));
        assertEquals(2, calls.get());
        assertEquals(2, exporter.getFinishedSpanItems().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void capturedMissingHeaderDoesNotBorrowTheOverlappingGlobalCarrier(boolean executionView) throws Exception {
        var property = "com.amazonaws.xray.traceHeader";
        var previous = System.getProperty(property);
        var conflictingTrace = "6955b900aaaaaaaaaaaaaaaaaaaaaaaa";
        System.setProperty(property, "Root=1-6955b900-aaaaaaaaaaaaaaaaaaaaaaaa;Sampled=0");
        var barrier = new CyclicBarrier(2);
        var sharedExtractor = new XRayContextExtractor();
        try {
            var missing = CompletableFuture.runAsync(() -> {
                var exporter = InMemorySpanExporter.create();
                var builder = SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter));
                var config = OtelPluginConfig.builder()
                        .enableMdc(false)
                        .contextExtractor(sharedExtractor)
                        .build();
                DurableExecutionPlugin plugin = executionView
                        ? new ExecutionOtelPlugin(builder, config)
                        : new InvocationOtelPlugin(builder, config);
                var info = new InvocationInfo("missing", "arn:missing", true, Instant.EPOCH);
                plugin.onInvocationStart(withHeader(info, "")); // API available, but this invocation has no header.
                await(barrier);
                plugin.onInvocationEnd(
                        new InvocationEndInfo("missing", "arn:missing", true, InvocationStatus.SUCCEEDED, null));
                var spans = exporter.getFinishedSpanItems();
                assertEquals(2, spans.size(), "global Sampled=0 must not suppress this headerless invocation");
                assertTrue(spans.stream().allMatch(span -> !conflictingTrace.equals(span.getTraceId())));
            });
            var present = CompletableFuture.runAsync(() -> {
                var extracted = sharedExtractor.extract(withHeader(
                        new InvocationInfo("present", "arn:present", true, Instant.EPOCH),
                        "Root=1-6955b900-123456789012345678901234;Sampled=1"));
                assertEquals("6955b900123456789012345678901234", extracted.traceId());
                assertEquals(ExtractedContext.Sampling.SAMPLED, extracted.sampling());
                await(barrier);
            });
            CompletableFuture.allOf(missing, present).get(20, TimeUnit.SECONDS);
            assertNull(sharedExtractor.extract(
                    withHeader(new InvocationInfo("missing", "arn:missing", true, Instant.EPOCH), "")));
            assertEquals(
                    conflictingTrace,
                    sharedExtractor.extract().traceId(),
                    "legacy direct extraction keeps its fallback");
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static void runInvocations(boolean executionView, boolean sampled, CyclicBarrier barrier) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder().enableMdc(false).build();
        DurableExecutionPlugin plugin =
                executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
        var traceId = sampled ? "6955b900123456789012345678901234" : "6955b900aaaaaaaaaaaaaaaaaaaaaaaa";
        var header = "Root=1-" + traceId.substring(0, 8) + "-" + traceId.substring(8)
                + ";Parent=1234567890123456;Sampled=" + (sampled ? "1" : "0");
        var arn = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/" + sampled;
        for (int invocation = 0; invocation < 2; invocation++) {
            plugin.onInvocationStart(withHeader(
                    new InvocationInfo(
                            "request-" + invocation, arn, invocation == 0, Instant.parse("2026-10-02T00:00:00Z")),
                    header));
            await(barrier);
            plugin.onInvocationEnd(new InvocationEndInfo(
                    "request-" + invocation,
                    arn,
                    invocation == 0,
                    invocation == 0 ? InvocationStatus.PENDING : InvocationStatus.SUCCEEDED,
                    null));
        }
        var spans = exporter.getFinishedSpanItems();
        if (!sampled) {
            assertTrue(spans.isEmpty(), "Upstream Sampled=0 overrides always-on fallback");
            return;
        }
        assertEquals(3, spans.size());
        assertTrue(spans.stream().allMatch(span -> traceId.equals(span.getTraceId())));
        assertTrue(spans.stream().allMatch(span -> "1234567890123456".equals(span.getParentSpanId())));
    }

    private static InvocationInfo withHeader(InvocationInfo info, String header) {
        return new InvocationInfo(
                info.requestId(),
                info.durableExecutionArn(),
                info.isFirstInvocation(),
                info.executionStartTime(),
                info.executionInput(),
                info.operations(),
                info.updatedOperations(),
                header);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
