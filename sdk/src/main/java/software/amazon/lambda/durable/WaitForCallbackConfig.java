// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * Configuration for the {@code waitForCallback} composite operation.
 *
 * <p>Combines a {@link StepConfig} (for the step that produces the callback) and a {@link CallbackConfig} (for the
 * callback wait itself).
 */
public class WaitForCallbackConfig {
    private final StepConfig stepConfig;
    private final CallbackConfig callbackConfig;

    private WaitForCallbackConfig(Builder builder) {
        this.stepConfig = builder.stepConfig == null ? StepConfig.builder().build() : builder.stepConfig;
        this.callbackConfig =
                builder.callbackConfig == null ? CallbackConfig.builder().build() : builder.callbackConfig;
    }

    /** Returns the step configuration for the composite operation. */
    public StepConfig stepConfig() {
        return stepConfig;
    }

    /** Returns the callback configuration for the composite operation. */
    public CallbackConfig callbackConfig() {
        return callbackConfig;
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a builder pre-populated with this instance's values. */
    public Builder toBuilder() {
        return new Builder().stepConfig(this.stepConfig).callbackConfig(this.callbackConfig);
    }

    /** Builder for {@link WaitForCallbackConfig}. */
    public static class Builder {
        private StepConfig stepConfig;
        private CallbackConfig callbackConfig;

        private Builder() {}

        public Builder stepConfig(StepConfig stepConfig) {
            this.stepConfig = stepConfig;
            return this;
        }

        public Builder callbackConfig(CallbackConfig callbackConfig) {
            this.callbackConfig = callbackConfig;
            return this;
        }

        public WaitForCallbackConfig build() {
            return new WaitForCallbackConfig(this);
        }
    }
}
