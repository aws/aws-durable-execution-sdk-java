// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for a stateful extension STEP primitive.
 *
 * @param <T> the checkpointed state and final result type
 */
public final class ExtensionStepConfig<T> {
    private final T initialState;
    private final SerDes serDes;

    private ExtensionStepConfig(Builder<T> builder) {
        initialState = builder.initialState;
        serDes = builder.serDes;
    }

    /** Returns the state supplied to the first attempt. */
    public T initialState() {
        return initialState;
    }

    /** Returns the custom serializer, or {@code null} to use the durable configuration default. */
    public SerDes serDes() {
        return serDes;
    }

    /** Returns a builder for a stateful extension step. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Builder for {@link ExtensionStepConfig}. */
    public static final class Builder<T> {
        private T initialState;
        private SerDes serDes;

        private Builder() {}

        /** Sets the state supplied to the first attempt. */
        public Builder<T> initialState(T initialState) {
            this.initialState = initialState;
            return this;
        }

        /** Sets the serializer for checkpointed state and the final result. */
        public Builder<T> serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        /** Builds the immutable configuration. */
        public ExtensionStepConfig<T> build() {
            return new ExtensionStepConfig<>(this);
        }
    }
}
