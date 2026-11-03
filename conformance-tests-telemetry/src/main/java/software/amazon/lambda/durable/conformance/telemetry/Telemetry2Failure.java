// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.telemetry.ContentConfig;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetry;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryConfig;
import software.amazon.lambda.durable.telemetry.exporters.CloudWatchLogsExporter;

/**
 * Failed single-step scenario for requirement telemetry-2.
 *
 * <p>The step {@code charge} throws {@code card declined}. The retry strategy declines, so the step fails on its first
 * attempt. The handler catches the step error and throws {@code payment failed} with the step error as its cause. So
 * the execution fails and the exported record carries a cause chain on the execution {@code error} and an
 * {@code error} on {@code operationsByName.charge}. telemetry-2 pins the messages {@code card declined} and
 * {@code payment failed}. Do not change them.
 *
 * <p>The handler configures the telemetry plugin exactly as {@link Telemetry1Success}. It constructs no record and
 * calls no exporter method. The plugin builds and exports the record.
 */
public final class Telemetry2Failure extends DurableHandler<Telemetry2Failure.Input, Void> {

    // The deployed function must export to the log group the runner reads.
    // A missing variable would produce a function that exports nowhere.
    // So class initialization fails when the variable is unset.
    private static final String LOG_GROUP_NAME = System.getenv("TELEMETRY_LOG_GROUP_NAME");

    static {
        if (LOG_GROUP_NAME == null || LOG_GROUP_NAME.isBlank()) {
            throw new IllegalStateException("TELEMETRY_LOG_GROUP_NAME must be set");
        }
    }

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(DurableExecutionTelemetry.durableExecutionTelemetry(
                        DurableExecutionTelemetryConfig.builder()
                                .addExporter(CloudWatchLogsExporter.builder()
                                        .logGroupName(LOG_GROUP_NAME)
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

    @Override
    public Void handleRequest(Input input, DurableContext context) {
        try {
            context.step(
                    "charge",
                    Void.class,
                    step -> {
                        throw new RuntimeException("card declined");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        } catch (RuntimeException stepError) {
            throw new RuntimeException("payment failed", stepError);
        }
        return null;
    }

    /** Event payload: {"value": 41}. */
    public static final class Input {
        public int value;

        public Input() {}
    }
}
