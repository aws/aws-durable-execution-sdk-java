// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.util.Objects;
import software.amazon.lambda.durable.config.RunInChildContextConfig;

/** Extension-only policies for an advanced CONTEXT primitive. */
public final class ExtensionContextConfig {
    private final RunInChildContextConfig childContextConfig;
    private final ExtensionContextErrorHandler errorHandler;
    private final boolean emitUserFunctionEvents;
    private final boolean suppressLateChildCheckpoints;

    private ExtensionContextConfig(Builder builder) {
        childContextConfig =
                Objects.requireNonNullElseGet(builder.childContextConfig, () -> RunInChildContextConfig.builder()
                        .build());
        errorHandler = builder.errorHandler;
        emitUserFunctionEvents = builder.emitUserFunctionEvents;
        suppressLateChildCheckpoints = builder.suppressLateChildCheckpoints;
    }

    public RunInChildContextConfig childContextConfig() {
        return childContextConfig;
    }

    public ExtensionContextErrorHandler errorHandler() {
        return errorHandler;
    }

    public boolean emitUserFunctionEvents() {
        return emitUserFunctionEvents;
    }

    public boolean suppressLateChildCheckpoints() {
        return suppressLateChildCheckpoints;
    }

    public Builder toBuilder() {
        return new Builder()
                .childContextConfig(childContextConfig)
                .errorHandler(errorHandler)
                .emitUserFunctionEvents(emitUserFunctionEvents)
                .suppressLateChildCheckpoints(suppressLateChildCheckpoints);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RunInChildContextConfig childContextConfig;
        private ExtensionContextErrorHandler errorHandler;
        private boolean emitUserFunctionEvents = true;
        private boolean suppressLateChildCheckpoints;

        private Builder() {}

        public Builder childContextConfig(RunInChildContextConfig childContextConfig) {
            this.childContextConfig = Objects.requireNonNull(childContextConfig, "childContextConfig cannot be null");
            return this;
        }

        public Builder errorHandler(ExtensionContextErrorHandler errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public Builder emitUserFunctionEvents(boolean emitUserFunctionEvents) {
            this.emitUserFunctionEvents = emitUserFunctionEvents;
            return this;
        }

        public Builder suppressLateChildCheckpoints(boolean suppressLateChildCheckpoints) {
            this.suppressLateChildCheckpoints = suppressLateChildCheckpoints;
            return this;
        }

        public ExtensionContextConfig build() {
            return new ExtensionContextConfig(this);
        }
    }
}
