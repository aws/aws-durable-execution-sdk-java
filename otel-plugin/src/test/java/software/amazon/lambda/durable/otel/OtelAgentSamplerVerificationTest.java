// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.internal.AutoConfigureListener;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtelAgentSamplerVerificationTest {
    private static final String TRACE = "a".repeat(32);
    private static final String ARN = "arn:test:agent-sampler";

    @AfterEach
    void clearState() {
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        DurableSamplingDecision.clearSharedStateForTest();
    }

    @Test
    void onlyThePostBuildCallbackAuthorizesTheInstalledInstance() {
        var sampler = DurableSampler.wrap(Sampler.alwaysOn());
        var other = DurableSampler.wrap(Sampler.alwaysOn());
        OtelPluginAutoConfigurationState.markInstalled();
        try (var sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(
                        SdkTracerProvider.builder().setSampler(sampler).build())
                .build()) {
            assertFalse(confirm(sampler));
            AutoConfigureListener listener = new OtelPluginAutoConfigurationCustomizerProvider();
            listener.afterAutoConfigure(sdk);
            assertTrue(confirm(sampler));
            assertFalse(confirm(other));
            assertSame(sampler, sdk.getSdkTracerProvider().getSampler());
            OtelPluginAutoConfigurationState.markInstalled();
            assertFalse(confirm(sampler), "A new configuration cannot reuse the previous verification");
        }
    }

    @Test
    void legacyConsumptionConfirmationCannotAuthorizePropagation() {
        var key = "software.amazon.lambda.durable.otel.scopedSamplingDecision."
                + Thread.currentThread().getId() + ".observation";
        var intent = DurableSamplingDecision.Intent.resolved(SamplingResult.recordAndSample());
        try (var observation = DurableSamplingDecision.observe(intent, TRACE, "Invocation", ARN)) {
            System.setProperty(key, "CONSUMED:" + TRACE + "\nInvocation\n" + ARN);
            assertFalse(observation.wasConsumed());
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void finalBuiltProviderWinsOverAnEarlierBuilderOrSamplerObservation() {
        var sampler = DurableSampler.wrap(Sampler.alwaysOn());
        var builder = SdkTracerProvider.builder().setSampler(sampler);
        AutoConfigureListener listener = new OtelPluginAutoConfigurationCustomizerProvider();
        try (var original =
                OpenTelemetrySdk.builder().setTracerProvider(builder.build()).build()) {
            listener.afterAutoConfigure(original);
            assertTrue(confirm(sampler));
            builder.setSampler(filter(sampler));
            listener.afterAutoConfigure(original);
            assertTrue(confirm(sampler), "Mutating the builder cannot change the already-built provider");
            try (var filtered = OpenTelemetrySdk.builder()
                    .setTracerProvider(builder.build())
                    .build()) {
                listener.afterAutoConfigure(filtered);
                assertFalse(confirm(sampler), "The inner DurableSampler is not the final sampler");
                assertFalse(confirm(filtered.getSdkTracerProvider().getSampler()));
            }
        }
    }

    private static boolean confirm(Sampler sampler) {
        var intent = DurableSamplingDecision.Intent.resolved(SamplingResult.recordAndSample());
        try (var observation = DurableSamplingDecision.observe(intent, TRACE, "Invocation", ARN)) {
            sampler.shouldSample(
                    Context.root(),
                    TRACE,
                    "Invocation",
                    SpanKind.INTERNAL,
                    Attributes.of(SpanAttributes.DURABLE_EXECUTION_ARN, ARN),
                    List.of());
            return observation.wasConsumed();
        }
    }

    private static Sampler filter(Sampler delegate) {
        return new Sampler() {
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                return name.equals("invoke")
                        ? SamplingResult.drop()
                        : delegate.shouldSample(parent, traceId, name, kind, attributes, links);
            }

            public String getDescription() {
                return "filter-around-durable-sampler";
            }
        };
    }
}
