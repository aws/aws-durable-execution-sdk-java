// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class InvocationHeaderTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void overlappingInvocationsAndResumeKeepTheirOwnHeader(boolean executionView) throws Exception {
        var barrier = new CyclicBarrier(2);
        var sampled = CompletableFuture.runAsync(() -> runInvocations(executionView, true, barrier));
        var unsampled = CompletableFuture.runAsync(() -> runInvocations(executionView, false, barrier));
        CompletableFuture.allOf(sampled, unsampled).get(20, TimeUnit.SECONDS);
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
            plugin.onInvocationStart(new InvocationInfo(
                    "request-" + invocation,
                    arn,
                    invocation == 0,
                    Instant.parse("2026-10-02T00:00:00Z"),
                    null,
                    Map.of(),
                    Map.of(),
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

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
