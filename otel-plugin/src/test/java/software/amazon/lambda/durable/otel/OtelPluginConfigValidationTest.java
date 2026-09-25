// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

class OtelPluginConfigValidationTest {

    @Test
    void configOnlyConstructorsRejectNullConfig() {
        var invocationError =
                assertThrows(NullPointerException.class, () -> new InvocationOtelPlugin((OtelPluginConfig) null));
        var executionError =
                assertThrows(NullPointerException.class, () -> new ExecutionOtelPlugin((OtelPluginConfig) null));

        assertEquals("config must not be null", invocationError.getMessage());
        assertEquals("config must not be null", executionError.getMessage());
    }

    @Test
    void builderConstructorsValidateConfigBeforeConsumingBuilder() {
        var invocationBuilder = SdkTracerProvider.builder();
        var executionBuilder = SdkTracerProvider.builder();

        assertThrows(NullPointerException.class, () -> new InvocationOtelPlugin(invocationBuilder, null));
        assertThrows(NullPointerException.class, () -> new ExecutionOtelPlugin(executionBuilder, null));

        try (var invocationProvider = invocationBuilder.build();
                var executionProvider = executionBuilder.build()) {
            assertNotNull(invocationProvider.get("probe"));
            assertNotNull(executionProvider.get("probe"));
        }
    }
}
