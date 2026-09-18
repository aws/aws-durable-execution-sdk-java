// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

/**
 * Covers registration-time rejection of a null config on both plugins' factory overloads.
 *
 * <p>The global-provider overload only stores the config, so a null one used to be dereferenced when an invocation's
 * plugin instance was built. {@code PluginRunner} contains a factory failure, so the function ran without the telemetry
 * it had asked for and reported one warning per invocation. Registration is where a caller can still act on it.
 */
class OtelPluginFactoryConfigTest {

    @Test
    void invocationPluginRejectsANullConfigOnTheGlobalProviderOverload() {
        var error =
                assertThrows(NullPointerException.class, () -> InvocationOtelPlugin.factory((OtelPluginConfig) null));

        assertTrue(error.getMessage().contains("config"), error.getMessage());
    }

    @Test
    void executionPluginRejectsANullConfigOnTheGlobalProviderOverload() {
        var error =
                assertThrows(NullPointerException.class, () -> ExecutionOtelPlugin.factory((OtelPluginConfig) null));

        assertTrue(error.getMessage().contains("config"), error.getMessage());
    }

    @Test
    void invocationPluginRejectsANullConfigOnTheProviderBuilderOverload() {
        assertThrows(NullPointerException.class, () -> InvocationOtelPlugin.factory(SdkTracerProvider.builder(), null));
    }

    @Test
    void executionPluginRejectsANullConfigOnTheProviderBuilderOverload() {
        assertThrows(NullPointerException.class, () -> ExecutionOtelPlugin.factory(SdkTracerProvider.builder(), null));
    }
}
