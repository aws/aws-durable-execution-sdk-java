// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.internal.AutoConfigureListener;

/**
 * Wraps the Java agent's configured ID generator with scoped durable-execution overrides so durable spans get
 * deterministic IDs.
 */
public final class OtelPluginAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider, AutoConfigureListener {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        OtelPluginAutoConfigurationState.markInstalled();
        autoConfiguration.addTracerProviderCustomizer((builder, config) -> {
            DeterministicIdGenerator.installOn(builder);
            return builder;
        });
        // Wrap the agent-configured sampler so durable spans use the execution's single precomputed decision, applied
        // through the durable span's parent context, instead of re-invoking the configured sampler per span.
        autoConfiguration.addSamplerCustomizer((sampler, config) -> DurableSampler.wrap(sampler));
    }

    @Override
    public void afterAutoConfigure(OpenTelemetrySdk sdk) {
        // This callback runs after every customizer and provider build. Checking the immutable provider here catches
        // even a later customizer wrapping DurableSampler, without inspecting or modifying another extension's filter.
        OtelPluginAutoConfigurationState.recordConfiguredSampler(
                sdk.getSdkTracerProvider().getSampler());
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }
}
