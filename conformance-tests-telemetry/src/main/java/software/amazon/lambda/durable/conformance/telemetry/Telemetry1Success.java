// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Single successful step scenario for requirement telemetry-1.
 *
 * <p>The handler configures the telemetry plugin with one CloudWatch Logs exporter. It constructs no record and calls
 * no exporter method. The plugin builds and exports the record.
 */
public final class Telemetry1Success extends DurableHandler<Telemetry1Success.Input, Telemetry1Success.Output> {

    // Class initialization fails when the variable is unset. See TelemetryConformanceConfig.requireLogGroupName.
    private static final String LOG_GROUP_NAME =
            TelemetryConformanceConfig.requireLogGroupName(System.getenv(TelemetryConformanceConfig.LOG_GROUP_ENV));

    @Override
    protected DurableConfig createConfiguration() {
        return TelemetryConformanceConfig.durableConfig(LOG_GROUP_NAME);
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
