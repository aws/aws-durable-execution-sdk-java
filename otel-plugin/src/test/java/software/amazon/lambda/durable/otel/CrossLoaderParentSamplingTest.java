// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class CrossLoaderParentSamplingTest {
    @AfterEach
    void clearGlobalState() {
        GlobalOpenTelemetry.resetForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void agentProcessorCannotForwardDurableDecisionToApplicationProvider(boolean executionView) throws Exception {
        var observations = new ArrayList<Observation>();
        try (var agentLoader = DurableSamplingDecisionClassLoaderTest.pluginClassLoader();
                var application = SdkTracerProvider.builder()
                        .setSampler(DurableSampler.wrap(Sampler.alwaysOff()))
                        .build();
                var agent = agentProvider(agentLoader, application.get("application"), observations)) {
            GlobalOpenTelemetry.set(opaqueTelemetry(agent));
            OtelPluginAutoConfigurationState.markInstalled();
            var config = OtelPluginConfig.builder()
                    .contextExtractor(() -> new ExtractedContext(
                            "aabbccddee112233445566778899aabb", null, ExtractedContext.Sampling.SAMPLED))
                    .enableMdc(false)
                    .build();
            var factory = executionView ? ExecutionOtelPlugin.factory(config) : InvocationOtelPlugin.factory(config);
            var arn = "arn:aws:lambda:us-east-1:123:function:test/durable/exec";
            var info = new InvocationInfo("request", arn, true, Instant.EPOCH);
            var plugin = factory.createPlugin(info);
            plugin.onInvocationStart(info);
            plugin.onInvocationEnd(new InvocationEndInfo("request", arn, true, InvocationStatus.SUCCEEDED, null));
            assertTrue(observations.stream().anyMatch(o -> o.name().equals("Workflow")));
            assertTrue(observations.stream().anyMatch(o -> o.name().equals("DurableExecutionRoot")));
            for (var observation : observations) {
                assertFalse(
                        observation.auxiliaryRecording(), "forwarded parent leaked sampling for " + observation.name());
            }
        }
    }

    private static SdkTracerProvider agentProvider(
            ClassLoader loader, Tracer application, List<Observation> observations) throws Exception {
        var samplerClass = Class.forName(DurableSampler.class.getName(), true, loader);
        assertNotSame(DurableSampler.class, samplerClass);
        var wrap = samplerClass.getDeclaredMethod("wrap", Sampler.class);
        wrap.setAccessible(true);
        var sampler = (Sampler) wrap.invoke(null, Sampler.alwaysOn());
        var builder = SdkTracerProvider.builder()
                .setSampler(sampler)
                .addSpanProcessor(new ForwardingProcessor(application, observations));
        var ids = Class.forName(DeterministicIdGenerator.class.getName(), true, loader);
        var install = ids.getDeclaredMethod("installOn", SdkTracerProviderBuilder.class);
        install.setAccessible(true);
        install.invoke(null, builder);
        return builder.build();
    }

    private static OpenTelemetry opaqueTelemetry(SdkTracerProvider provider) {
        var opaque = new TracerProvider() {
            @Override
            public Tracer get(String name) {
                return provider.get(name);
            }

            @Override
            public Tracer get(String name, String version) {
                return provider.get(name, version);
            }
        };
        return new OpenTelemetry() {
            @Override
            public TracerProvider getTracerProvider() {
                return opaque;
            }

            @Override
            public ContextPropagators getPropagators() {
                return ContextPropagators.noop();
            }
        };
    }

    private record Observation(String name, boolean auxiliaryRecording) {}

    private record ForwardingProcessor(Tracer application, List<Observation> observations) implements SpanProcessor {
        @Override
        public void onStart(Context parent, ReadWriteSpan span) {
            var auxiliary = application
                    .spanBuilder("processor-auxiliary")
                    .setParent(parent)
                    .startSpan();
            observations.add(new Observation(span.getName(), auxiliary.isRecording()));
            auxiliary.end();
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {}

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }
}
