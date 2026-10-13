// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ReplacementSamplerPolicyTest {
    @ParameterizedTest
    @CsvSource({
        "invocation,false,root,false,plain", "execution,false,root,false,plain",
        "invocation,true,root,true,plain", "execution,true,root,true,plain",
        "invocation,false,sampled,true,plain", "execution,false,sampled,true,plain",
        "invocation,true,unsampled,false,plain", "execution,true,unsampled,false,plain",
        "invocation,false,root,false,local", "execution,false,root,false,local",
        "invocation,false,root,false,opaque", "execution,false,root,false,opaque"
    })
    void visibleReplacementParentBasedSamplerKeepsItsRootPolicy(
            String view, boolean rootSamples, String upstream, boolean expectedSampled, String topology) {
        var oldHeader = System.getProperty("com.amazonaws.xray.traceHeader");
        var evaluations = new AtomicInteger();
        var sideEffects = new AtomicInteger();
        var decisions = new CopyOnWriteArrayList<Boolean>();
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
                evaluations.incrementAndGet();
                assertFalse(Span.fromContext(parent).getSpanContext().isValid());
                return rootSamples ? SamplingResult.recordAndSample() : SamplingResult.drop();
            }

            public String getDescription() {
                return "counted-root-policy";
            }
        };
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        System.setProperty("com.amazonaws.xray.traceHeader", header);
        var configured = Sampler.parentBased(root);
        try (var provider = SdkTracerProvider.builder()
                .setSampler(topology.equals("plain") ? configured : DurableSampler.wrap(configured))
                .setIdGenerator(new DeterministicIdGenerator())
                .build()) {
            var opaque = new TracerProvider() {
                public Tracer get(String name) {
                    return provider.get(name);
                }

                public Tracer get(String name, String version) {
                    return provider.get(name, version);
                }
            };
            GlobalOpenTelemetry.set(new OpenTelemetry() {
                public TracerProvider getTracerProvider() {
                    return topology.equals("opaque") ? opaque : provider;
                }

                public ContextPropagators getPropagators() {
                    return ContextPropagators.noop();
                }
            });
            DurableExecutionPlugin plugin =
                    view.equals("invocation") ? new InvocationOtelPlugin() : new ExecutionOtelPlugin();
            var tracer = provider.get("application");
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        var span = tracer.spanBuilder("application-work").startSpan();
                        decisions.add(span.isRecording());
                        span.end();
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
            assertTrue(decisions.size() >= 2, "Initial invocation and actual replay must both execute the handler");
            assertTrue(decisions.stream().allMatch(decision -> decision == expectedSampled), decisions.toString());
            assertEquals(
                    upstream.equals("root") ? (topology.equals("opaque") ? 1 : decisions.size()) : 0,
                    evaluations.get());
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
