// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.sdk.trace.samplers.Sampler;

final class OtelPluginAutoConfigurationState {

    private static final String INSTALLED_PROPERTY =
            "software.amazon.lambda.durable.otel.autoConfigurationCustomizerProviderInstalled";
    // Kept in the agent class loader: only the exact final sampler may confirm an application's propagation request.
    private static volatile DurableSampler configuredSampler;

    private OtelPluginAutoConfigurationState() {}

    static boolean isInstalled() {
        return Boolean.getBoolean(INSTALLED_PROPERTY);
    }

    static void markInstalled() {
        configuredSampler = null;
        System.setProperty(INSTALLED_PROPERTY, Boolean.TRUE.toString());
    }

    static void recordConfiguredSampler(Sampler sampler) {
        configuredSampler = sampler instanceof DurableSampler durableSampler ? durableSampler : null;
    }

    static boolean isConfiguredSampler(DurableSampler sampler) {
        return sampler == configuredSampler;
    }

    static void resetInstalledForTest() {
        configuredSampler = null;
        System.clearProperty(INSTALLED_PROPERTY);
    }
}
