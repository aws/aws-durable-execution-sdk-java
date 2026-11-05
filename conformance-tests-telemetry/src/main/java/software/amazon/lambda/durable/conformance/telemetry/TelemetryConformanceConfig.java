// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.telemetry.ContentConfig;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetry;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryConfig;
import software.amazon.lambda.durable.telemetry.exporters.CloudWatchLogsExporter;

/**
 * Shared plugin setup for the telemetry conformance handlers.
 *
 * <p>Every handler in this module exports to the same log group with the same plugin settings. The runner reads that
 * log group and compares the record to the requirement. A handler with different settings would produce a record the
 * runner cannot match. So the settings live here once and each handler calls these methods.
 */
final class TelemetryConformanceConfig {

    /** Environment variable that names the log group the runner reads. */
    static final String LOG_GROUP_ENV = "TELEMETRY_LOG_GROUP_NAME";

    private TelemetryConformanceConfig() {}

    /**
     * Validates the configured log group name.
     *
     * <p>The deployed function must export to the log group the runner reads. A missing name would produce a function
     * that exports nowhere, and the runner would time out instead of reporting a configuration error. So this method
     * rejects a null or blank name, and the caller fails class initialization.
     *
     * @param logGroupName the value of {@link #LOG_GROUP_ENV}, possibly null
     * @return the same value when it is set and not blank
     * @throws IllegalStateException when the value is null or blank
     */
    static String requireLogGroupName(String logGroupName) {
        if (logGroupName == null || logGroupName.isBlank()) {
            throw new IllegalStateException(LOG_GROUP_ENV + " must be set");
        }
        return logGroupName;
    }

    /**
     * Builds the handler configuration with one telemetry plugin and one CloudWatch Logs exporter.
     *
     * <p>The exporter creates its client on first export, so calling this method makes no network call.
     *
     * @param logGroupName the log group the exporter writes to
     * @return the configuration a telemetry conformance handler returns from {@code createConfiguration()}
     */
    static DurableConfig durableConfig(String logGroupName) {
        return DurableConfig.builder()
                .withPlugins(
                        DurableExecutionTelemetry.durableExecutionTelemetry(DurableExecutionTelemetryConfig.builder()
                                .addExporter(CloudWatchLogsExporter.builder()
                                        .logGroupName(logGroupName)
                                        .build())
                                .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_COMPLETE)
                                .samplingRate(1.0)
                                .operationDetail(DurableExecutionTelemetryConfig.OperationDetail.TOP_LEVEL)
                                .content(ContentConfig.builder()
                                        .input(true)
                                        .output(true)
                                        .build())
                                .build()))
                .build();
    }
}
