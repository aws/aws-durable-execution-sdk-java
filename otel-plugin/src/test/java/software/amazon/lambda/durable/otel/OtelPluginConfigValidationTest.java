// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

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
    void invocationConstructorRejectsNullConfigBeforeTouchingBuilder() {
        var builder = spy(SdkTracerProvider.builder());

        assertThrows(NullPointerException.class, () -> new InvocationOtelPlugin(builder, null));

        verifyNoInteractions(builder);
    }

    @Test
    void executionConstructorRejectsNullConfigBeforeTouchingBuilder() {
        var builder = spy(SdkTracerProvider.builder());

        assertThrows(NullPointerException.class, () -> new ExecutionOtelPlugin(builder, null));

        verifyNoInteractions(builder);
    }
}
