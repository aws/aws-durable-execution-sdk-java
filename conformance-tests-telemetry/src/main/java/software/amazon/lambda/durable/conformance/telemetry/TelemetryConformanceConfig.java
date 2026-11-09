// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import java.util.Map;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.telemetry.ContentConfig;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetry;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryConfig;
import software.amazon.lambda.durable.telemetry.OperationOverride;
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

    /** Name of the telemetry-3 step. The override and the step call must use the same string. */
    static final String RESULT_SELECT_STEP_NAME = "fetch-user";

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
     * Builds the telemetry plugin settings: one CloudWatch Logs exporter for the given log group, emit on completion,
     * sample every execution, top-level operation detail, input and output included.
     *
     * <p>This method constructs no client. The exporter creates its CloudWatch Logs client on first export, and the
     * settings object is a plain value. So a unit test can call it with no credentials and no network.
     *
     * @param logGroupName the log group the exporter writes to
     * @return the plugin settings the telemetry-1 and telemetry-2 handlers use
     */
    static DurableExecutionTelemetryConfig telemetryConfig(String logGroupName) {
        return telemetryConfig(
                logGroupName, ContentConfig.builder().input(true).output(true).build());
    }

    /**
     * Builds the telemetry plugin settings with a caller-supplied content section.
     *
     * <p>Every handler shares the exporter, emit mode, sampling rate, and operation detail. Only the content section
     * differs per requirement. telemetry-1 and telemetry-2 include input and output. telemetry-3 excludes both and adds
     * a per-operation result override. So the shared settings live here and the content is a parameter.
     *
     * @param logGroupName the log group the exporter writes to
     * @param content the content section for the requirement the handler covers
     * @return the plugin settings with the shared fields fixed and the given content
     */
    static DurableExecutionTelemetryConfig telemetryConfig(String logGroupName, ContentConfig content) {
        return DurableExecutionTelemetryConfig.builder()
                .addExporter(CloudWatchLogsExporter.builder()
                        .logGroupName(logGroupName)
                        .build())
                .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_COMPLETE)
                .samplingRate(1.0)
                .operationDetail(DurableExecutionTelemetryConfig.OperationDetail.TOP_LEVEL)
                .content(content)
                .build();
    }

    /**
     * Builds the telemetry-3 settings: the shared exporter and modes, no input, no output, and one result override on
     * {@link #RESULT_SELECT_STEP_NAME} that applies {@link #selectUserId(Object)}.
     *
     * <p>telemetry-3 requires that the record carry no {@code input} and no {@code output} key, and that
     * {@code operationsByName.fetch-user.result} hold exactly {@code {"userId": "u-123"}}.
     *
     * @param logGroupName the log group the exporter writes to
     * @return the plugin settings the telemetry-3 handler uses
     */
    static DurableExecutionTelemetryConfig resultSelectTelemetryConfig(String logGroupName) {
        return telemetryConfig(
                logGroupName,
                ContentConfig.builder()
                        .input(false)
                        .output(false)
                        .addOverride(OperationOverride.withResult(
                                RESULT_SELECT_STEP_NAME, TelemetryConformanceConfig::selectUserId))
                        .build());
    }

    /**
     * Keeps {@code userId} and drops every other key of a parsed step result.
     *
     * <p>The plugin JSON-parses the checkpointed step result before it calls the transform. So the argument is a
     * detached {@code Map}, not the handler's output class and not a JSON string.
     *
     * @param result the parsed step result: a {@code Map} with {@code userId}, {@code email}, and {@code token}
     * @return a new single-entry map
     */
    static Object selectUserId(Object result) {
        return Map.of("userId", ((Map<?, ?>) result).get("userId"));
    }

    /**
     * Builds the handler configuration with the telemetry plugin from {@link #telemetryConfig(String)}.
     *
     * <p>{@code DurableConfig.build()} constructs the default Lambda client, and that constructor sends one warm-up
     * request. So this method is for the deployed handler only. Tests check {@link #telemetryConfig(String)} instead.
     *
     * @param logGroupName the log group the exporter writes to
     * @return the configuration a telemetry conformance handler returns from {@code createConfiguration()}
     */
    static DurableConfig durableConfig(String logGroupName) {
        return durableConfig(telemetryConfig(logGroupName));
    }

    /**
     * Builds the handler configuration with the telemetry plugin from the given settings.
     *
     * <p>{@code DurableConfig.build()} constructs the default Lambda client, and that constructor sends one warm-up
     * request. So this method is for the deployed handler only.
     *
     * @param telemetryConfig the plugin settings, from one of the {@code telemetryConfig} overloads
     * @return the configuration a telemetry conformance handler returns from {@code createConfiguration()}
     */
    static DurableConfig durableConfig(DurableExecutionTelemetryConfig telemetryConfig) {
        return DurableConfig.builder()
                .withPlugins(DurableExecutionTelemetry.durableExecutionTelemetry(telemetryConfig))
                .build();
    }
}
