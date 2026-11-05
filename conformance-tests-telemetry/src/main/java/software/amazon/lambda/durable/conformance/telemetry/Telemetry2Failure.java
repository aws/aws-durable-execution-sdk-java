// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Failed single-step scenario for requirement telemetry-2.
 *
 * <p>The step {@code charge} throws {@code card declined}. The retry strategy declines, so the step fails on its first
 * attempt. The handler catches the step error and throws {@code payment failed} with the step error as its cause. So
 * the execution fails and the exported record carries an {@code error} on the execution and on
 * {@code operationsByName.charge}. Error names and cause depth differ per SDK by design, so telemetry-2 asserts only
 * that each error is an object. It pins the messages {@code card declined} and {@code payment failed} through the core
 * execution-result and history sections. Do not change them.
 *
 * <p>The handler configures the telemetry plugin exactly as {@link Telemetry1Success}. It constructs no record and
 * calls no exporter method. The plugin builds and exports the record.
 */
public final class Telemetry2Failure extends DurableHandler<Telemetry2Failure.Input, Void> {

    // Class initialization fails when the variable is unset. See TelemetryConformanceConfig.requireLogGroupName.
    private static final String LOG_GROUP_NAME =
            TelemetryConformanceConfig.requireLogGroupName(System.getenv(TelemetryConformanceConfig.LOG_GROUP_ENV));

    @Override
    protected DurableConfig createConfiguration() {
        return TelemetryConformanceConfig.durableConfig(LOG_GROUP_NAME);
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
            // The SDK rebuilds the step's original exception from the checkpoint and rethrows it, so the
            // step surfaces as RuntimeException("card declined"). StepFailedException is only the fallback
            // when that rebuild fails. Catching StepFailedException would let "card declined" escape.
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
