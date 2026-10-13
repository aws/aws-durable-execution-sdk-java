// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class VisibleReplacementSdkSpanPolicyTest {
    @ParameterizedTest
    @CsvSource({
        "invocation,false,root,false", "execution,false,root,false",
        "invocation,true,root,true", "execution,true,root,true",
        "invocation,false,sampled,true", "execution,false,sampled,true",
        "invocation,true,unsampled,false", "execution,true,unsampled,false"
    })
    void actualSdkSpansHonorVisibleReplacementRootPolicy(
            String view, boolean rootSamples, String upstream, boolean expectedSampled) {
        var oldHeader = System.getProperty("com.amazonaws.xray.traceHeader");
        var rootPolicySnapshots = new AtomicInteger();
        var invocations = new AtomicInteger();
        var sideEffects = new AtomicInteger();
        var header = "Root=1-6955b900-123456789012345678901234";
        if (upstream.equals("sampled")) header += ";Sampled=1";
        else if (upstream.equals("unsampled")) header += ";Sampled=0";
        var root = new Sampler() {
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                assertFalse(Span.fromContext(parent).getSpanContext().isValid());
                // Count the SDK's root-policy snapshot, excluding normal provider sampling for a real synthetic-root
                // export on branches that implement it. No exactly-once promise is made for a plain provider.
                if (!name.equals("DurableExecutionRoot")
                        && attributes.get(AttributeKey.stringKey("durable.execution.arn")) != null) {
                    rootPolicySnapshots.incrementAndGet();
                }
                return rootSamples ? SamplingResult.recordAndSample() : SamplingResult.drop();
            }

            public String getDescription() {
                return "counted-visible-root-policy";
            }
        };
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        System.setProperty("com.amazonaws.xray.traceHeader", header);
        try (var exporter = InMemorySpanExporter.create();
                var provider = SdkTracerProvider.builder()
                        .setSampler(Sampler.parentBased(root))
                        .setIdGenerator(new DeterministicIdGenerator())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            GlobalOpenTelemetry.set(new OpenTelemetry() {
                public TracerProvider getTracerProvider() {
                    return provider;
                }

                public ContextPropagators getPropagators() {
                    return ContextPropagators.noop();
                }
            });
            DurableExecutionPlugin plugin =
                    view.equals("invocation") ? new InvocationOtelPlugin() : new ExecutionOtelPlugin();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        invocations.incrementAndGet();
                        context.step("saved", String.class, step -> {
                            sideEffects.incrementAndGet();
                            return "saved";
                        });
                        context.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    },
                    DurableConfig.builder().withPlugins(plugin).build());
            assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());
            assertTrue(invocations.get() >= 2);
            var sdkSpans = exporter.getFinishedSpanItems().stream()
                    .filter(span ->
                            span.getInstrumentationScopeInfo().getName().equals("aws-durable-execution-sdk-java"))
                    // A plain provider keeps its native policy for parentless synthetic-root exports. Full SDK
                    // sampling overrides require DurableSampler; this regression concerns regular SDK descendants.
                    .filter(span -> !span.getName().equals("DurableExecutionRoot"))
                    .toList();
            if (expectedSampled) {
                assertFalse(sdkSpans.isEmpty());
                assertTrue(
                        sdkSpans.stream().allMatch(span -> span.getSpanContext().isSampled()));
            } else assertTrue(sdkSpans.isEmpty(), sdkSpans.toString());
            assertEquals(upstream.equals("root") ? invocations.get() : 0, rootPolicySnapshots.get());
            assertEquals(1, sideEffects.get());
        } finally {
            GlobalOpenTelemetry.resetForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
            DurableSamplingDecision.clearSharedStateForTest();
            if (oldHeader == null) System.clearProperty("com.amazonaws.xray.traceHeader");
            else System.setProperty("com.amazonaws.xray.traceHeader", oldHeader);
        }
    }
}
