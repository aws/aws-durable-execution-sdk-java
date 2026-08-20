// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/** Wraps the Java agent's configured ID generator with scoped durable-execution overrides. */
public final class OtelPluginAutoConfigurationCustomizerProvider implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        OtelPluginAutoConfigurationState.markInstalled();
        autoConfiguration.addTracerProviderCustomizer((builder, config) -> {
            DeterministicIdGenerator.installOn(builder);
            return builder;
        });
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }
}
