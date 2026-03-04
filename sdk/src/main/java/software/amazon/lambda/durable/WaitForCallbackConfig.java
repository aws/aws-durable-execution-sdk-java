// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

public class WaitForCallbackConfig {
    private final StepConfig stepConfig;
    private final CallbackConfig callbackConfig;

    private WaitForCallbackConfig(Builder builder) {
        this.stepConfig = builder.stepConfig == null ? StepConfig.builder().build() : builder.stepConfig;
        this.callbackConfig =
                builder.callbackConfig == null ? CallbackConfig.builder().build() : builder.callbackConfig;
    }

    public StepConfig stepConfig() {
        return stepConfig;
    }

    public CallbackConfig callbackConfig() {
        return callbackConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().stepConfig(this.stepConfig).callbackConfig(this.callbackConfig);
    }

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
