// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Per-operation result selection for requirement telemetry-3.
 *
 * <p>The step {@code fetch-user} returns {@code {userId, email, token}}. The plugin exports an operation's result only
 * when an {@code OperationOverride.withResult} transform opts the operation in. The transform
 * {@link TelemetryConformanceConfig#selectUserId(Object)} returns a new map that holds {@code userId} alone. So the
 * record's {@code operationsByName.fetch-user.result} is exactly {@code {"userId": "u-123"}}, and {@code email} and
 * {@code token} never reach CloudWatch Logs.
 *
 * <p>The content section also excludes input and output. telemetry-3 requires that the record carry no {@code input}
 * and no {@code output} key. The execution result still holds all three keys, so the core {@code ExpectedResult} check
 * proves the transform removed data from the record, not from the step.
 *
 * <p>The handler constructs no record and calls no exporter method. The plugin builds and exports the record.
 */
public final class Telemetry3ResultSelect
        extends DurableHandler<Telemetry3ResultSelect.Input, Telemetry3ResultSelect.Output> {

    // Class initialization fails when the variable is unset. See TelemetryConformanceConfig.requireLogGroupName.
    private static final String LOG_GROUP_NAME =
            TelemetryConformanceConfig.requireLogGroupName(System.getenv(TelemetryConformanceConfig.LOG_GROUP_ENV));

    @Override
    protected DurableConfig createConfiguration() {
        return TelemetryConformanceConfig.durableConfig(
                TelemetryConformanceConfig.resultSelectTelemetryConfig(LOG_GROUP_NAME));
    }

    @Override
    public Output handleRequest(Input input, DurableContext context) {
        return context.step(
                TelemetryConformanceConfig.RESULT_SELECT_STEP_NAME,
                Output.class,
                step -> new Output(input.userId, "test@example.com", "tok-456"));
    }

    /** Event payload: {"userId": "u-123"}. */
    public static final class Input {
        public String userId;

        public Input() {}
    }

    /** Step and execution result: {"userId": "u-123", "email": "test@example.com", "token": "tok-456"}. */
    public static final class Output {
        public String userId;
        public String email;
        public String token;

        public Output() {}

        public Output(String userId, String email, String token) {
            this.userId = userId;
            this.email = email;
            this.token = token;
        }
    }
}
