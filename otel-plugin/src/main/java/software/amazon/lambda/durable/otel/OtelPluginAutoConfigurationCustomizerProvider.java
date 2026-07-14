// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * Installs the durable-execution ID generator when the OpenTelemetry Java agent auto-configures the SDK.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class OtelPluginAutoConfigurationCustomizerProvider implements AutoConfigurationCustomizerProvider {

    private static final DeterministicIdGenerator ID_GENERATOR = new DeterministicIdGenerator();

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((builder, config) -> builder.setIdGenerator(ID_GENERATOR));
    }

    static DeterministicIdGenerator idGenerator() {
        return ID_GENERATOR;
    }
}
