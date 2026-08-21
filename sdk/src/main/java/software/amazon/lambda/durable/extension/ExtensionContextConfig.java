// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import software.amazon.lambda.durable.serde.SerDes;

/** Extension-only policies for an advanced CONTEXT primitive. */
public final class ExtensionContextConfig {
    private final SerDes serDes;
    private final boolean virtual;
    private final ExtensionContextErrorHandler errorHandler;
    private final boolean emitUserFunctionEvents;
    private final boolean suppressLateChildCheckpoints;
    private final boolean validateCompletedReplay;

    private ExtensionContextConfig(Builder builder) {
        serDes = builder.serDes;
        virtual = builder.virtual;
        errorHandler = builder.errorHandler;
        emitUserFunctionEvents = builder.emitUserFunctionEvents;
        suppressLateChildCheckpoints = builder.suppressLateChildCheckpoints;
        validateCompletedReplay = builder.validateCompletedReplay;
    }

    public SerDes serDes() {
        return serDes;
    }

    public boolean isVirtual() {
        return virtual;
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

    public boolean validateCompletedReplay() {
        return validateCompletedReplay;
    }

    public Builder toBuilder() {
        return new Builder()
                .serDes(serDes)
                .isVirtual(virtual)
                .errorHandler(errorHandler)
                .emitUserFunctionEvents(emitUserFunctionEvents)
                .suppressLateChildCheckpoints(suppressLateChildCheckpoints)
                .validateCompletedReplay(validateCompletedReplay);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private SerDes serDes;
        private boolean virtual;
        private ExtensionContextErrorHandler errorHandler;
        private boolean emitUserFunctionEvents = true;
        private boolean suppressLateChildCheckpoints;
        private boolean validateCompletedReplay;

        private Builder() {}

        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        public Builder isVirtual(boolean virtual) {
            this.virtual = virtual;
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

        public Builder validateCompletedReplay(boolean validateCompletedReplay) {
            this.validateCompletedReplay = validateCompletedReplay;
            return this;
        }

        public ExtensionContextConfig build() {
            return new ExtensionContextConfig(this);
        }
    }
}
