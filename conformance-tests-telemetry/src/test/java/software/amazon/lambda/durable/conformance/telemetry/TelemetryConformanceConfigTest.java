// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryConfig.EmitMode;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryConfig.OperationDetail;
import software.amazon.lambda.durable.telemetry.exporters.CloudWatchLogsExporter;

/**
 * Tests the shared handler setup through the helper with explicit values.
 *
 * <p>The handlers read the log group name in a static initializer, so the guard is tested here, not through the
 * environment. {@code durableConfig} builds a {@code DurableConfig}, whose constructor creates the default Lambda
 * client and sends a warm-up request. So these tests check {@code telemetryConfig}, which constructs no client.
 */
class TelemetryConformanceConfigTest {

    private static final String LOG_GROUP = "/aws/lambda/durable-execution-telemetry";

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void requireLogGroupNameRejectsMissingValue(String value) {
        var e = assertThrows(IllegalStateException.class, () -> TelemetryConformanceConfig.requireLogGroupName(value));
        assertEquals("TELEMETRY_LOG_GROUP_NAME must be set", e.getMessage());
    }

    @Test
    void requireLogGroupNameReturnsSetValue() {
        assertSame(LOG_GROUP, TelemetryConformanceConfig.requireLogGroupName(LOG_GROUP));
    }

    @Test
    void telemetryConfigUsesOneCloudWatchLogsExporter() {
        var config = TelemetryConformanceConfig.telemetryConfig(LOG_GROUP);
        assertEquals(1, config.exporters().size());
        // The exporter exposes no log group accessor, so the name is not asserted here. The handler passes its
        // static LOG_GROUP_NAME straight through, and the deployed run matches no record if the name is wrong.
        assertInstanceOf(CloudWatchLogsExporter.class, config.exporters().get(0));
    }

    @Test
    void telemetryConfigMatchesTheRunnerExpectations() {
        var config = TelemetryConformanceConfig.telemetryConfig(LOG_GROUP);
        // The runner reads one record per execution with its input and output. These four settings produce that.
        assertEquals(EmitMode.ON_COMPLETE, config.emitMode());
        assertEquals(1.0, config.samplingRate());
        assertEquals(OperationDetail.TOP_LEVEL, config.operationDetail());
        assertTrue(config.content().includeInput());
        assertTrue(config.content().includeOutput());
    }
}
