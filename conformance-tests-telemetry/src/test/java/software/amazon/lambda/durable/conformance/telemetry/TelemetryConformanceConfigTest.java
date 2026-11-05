// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;

/**
 * Tests the shared handler setup. The handlers read the log group name in a static initializer, so the guard and the
 * configuration are tested here through the helper with explicit values instead of through the environment.
 */
class TelemetryConformanceConfigTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void requireLogGroupNameRejectsMissingValue(String value) {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> TelemetryConformanceConfig.requireLogGroupName(value));
        assertEquals("TELEMETRY_LOG_GROUP_NAME must be set", e.getMessage());
    }

    @Test
    void requireLogGroupNameReturnsSetValue() {
        String name = "/aws/lambda/durable-execution-telemetry";
        assertSame(name, TelemetryConformanceConfig.requireLogGroupName(name));
    }

    @Test
    void durableConfigRegistersOneTelemetryPlugin() {
        DurableConfig config = TelemetryConformanceConfig.durableConfig("/aws/lambda/durable-execution-telemetry");
        // The factory is a lambda, so its type carries no useful name. One registered factory is the contract.
        assertEquals(1, config.getPluginFactories().size());
    }
}
