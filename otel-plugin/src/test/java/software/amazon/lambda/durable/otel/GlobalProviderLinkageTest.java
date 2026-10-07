// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GlobalProviderLinkageTest {
    @BeforeEach
    void markCustomizerInstalled() {
        OtelPluginAutoConfigurationState.markInstalled();
    }

    @AfterEach
    void resetCustomizer() {
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableGlobalMethodDisablesTelemetryLocally(boolean getterFails) {
        try (var global = mockStatic(GlobalOpenTelemetry.class)) {
            if (getterFails) {
                global.when(GlobalOpenTelemetry::isSet).thenReturn(true);
                global.when(GlobalOpenTelemetry::getOrNoop).thenThrow(new NoSuchMethodError("old API getter"));
            } else {
                global.when(GlobalOpenTelemetry::isSet).thenThrow(new NoSuchMethodError("old API probe"));
            }
            assertNull(OtelPluginSupport.tryResolveGlobalProvider("scope", "test-plugin"));
            global.verify(GlobalOpenTelemetry::get, never());
        }
    }

    @Test
    void fatalJvmFailureStillEscapesProviderLookup() {
        var fatal = new InternalError("fatal JVM failure");
        try (var global = mockStatic(GlobalOpenTelemetry.class)) {
            global.when(GlobalOpenTelemetry::isSet).thenThrow(fatal);
            assertSame(
                    fatal,
                    assertThrows(
                            InternalError.class,
                            () -> OtelPluginSupport.tryResolveGlobalProvider("scope", "test-plugin")));
        }
    }
}
