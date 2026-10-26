// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.telemetry.ContentConfig;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetry;
import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryConfig;
import software.amazon.lambda.durable.telemetry.exporters.CloudWatchLogsExporter;

/**
 * Single successful step scenario for requirement telemetry-1.
 *
 * <p>The handler configures the telemetry plugin with one CloudWatch Logs exporter. It constructs no record and calls
 * no exporter method. The plugin builds and exports the record.
 */
public final class Telemetry1Success extends DurableHandler<Telemetry1Success.Input, Telemetry1Success.Output> {

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
    public Output handleRequest(Input input, DurableContext context) {
        int value = context.step("increment", Integer.class, step -> input.value + 1);
        return new Output(value);
    }

    /** Event payload: {"value": 41}. */
    public static final class Input {
        public int value;

        public Input() {}
    }

    /** Result payload: {"value": 42}. */
    public static final class Output {
        public int value;

        public Output() {}

        public Output(int value) {
            this.value = value;
        }
    }
}
