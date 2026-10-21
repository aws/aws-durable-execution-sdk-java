// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.PluginRunner;

class PluginStartupOrderTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void earlierInitializerContextIsAvailableToOtelFactory(boolean executionView) {
        var carrier = new ThreadLocal<ExtractedContext>();
        var expected = new ExtractedContext(
                "6955b900123456789012345678901234", "1234567890123456", ExtractedContext.Sampling.SAMPLED);
        DurableExecutionPluginFactory initializer = info -> new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                carrier.set(expected);
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                carrier.remove();
            }
        };
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOff())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(carrier::get)
                .build();
        var otel = executionView
                ? ExecutionOtelPlugin.factory(builder, config)
                : InvocationOtelPlugin.factory(builder, config);
        var runner = new PluginRunner(List.of(initializer, otel));
        try {
            runner.onInvocationStart(new InvocationInfo("request", "arn", true, Instant.EPOCH));
            runner.onInvocationEnd(new InvocationEndInfo("request", "arn", true, InvocationStatus.SUCCEEDED, null));
            var spans = exporter.getFinishedSpanItems();
            assertEquals(2, spans.size(), "Initializer's sampled context must override the always-off fallback");
            assertTrue(spans.stream().allMatch(span -> expected.traceId().equals(span.getTraceId())));
            assertTrue(spans.stream().allMatch(span -> expected.parentSpanId().equals(span.getParentSpanId())));
        } finally {
            carrier.remove();
            runner.releasePlugins();
        }
    }
}
