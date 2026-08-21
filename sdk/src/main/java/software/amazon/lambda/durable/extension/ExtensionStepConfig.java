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
    private final RetryStrategy<T> retryStrategy;
    private final StepSemantics semanticsPerRetry;

    private ExtensionStepConfig(Builder<T> builder) {
        initialState = builder.initialState;
        serDes = builder.serDes;
        retryStrategy = builder.retryStrategy;
        semanticsPerRetry = builder.semanticsPerRetry;
    }

    /** Returns the state supplied to the first attempt. */
    public T initialState() {
        return initialState;
    }

    /** Returns the custom serializer, or {@code null} to use the durable configuration default. */
    public SerDes serDes() {
        return serDes;
    }

    /** Returns the exception retry strategy, or {@code null} when thrown exceptions are terminal. */
    public RetryStrategy<T> retryStrategy() {
        return retryStrategy;
    }

    /** Returns the delivery semantics used for each attempt. */
    public StepSemantics semanticsPerRetry() {
        return semanticsPerRetry != null ? semanticsPerRetry : StepSemantics.AT_LEAST_ONCE_PER_RETRY;
    }

    /** Returns a builder initialized from this configuration. */
    public Builder<T> toBuilder() {
        return new Builder<T>()
                .initialState(initialState)
                .serDes(serDes)
                .retryStrategy(retryStrategy)
                .semanticsPerRetry(semanticsPerRetry);
    }

    /** Returns a builder for a stateful extension step. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Delivery semantics for each extension step attempt. */
    public enum StepSemantics {
        /** The step may be re-executed if an attempt is interrupted. */
        AT_LEAST_ONCE_PER_RETRY,

        /** The START checkpoint is awaited so an interrupted attempt is not re-executed. */
        AT_MOST_ONCE_PER_RETRY
    }

    /** Determines whether a thrown exception should retry the extension step. */
    @FunctionalInterface
    public interface RetryStrategy<T> {
        /**
         * Returns the retry decision for a failed attempt.
         *
         * @param error the thrown exception
         * @param state state supplied to the failed attempt
         * @param attempt the current one-based attempt number
         * @return a retry outcome with the next state and delay, or a do-not-retry decision
         */
        ExtensionStepResult.RetryDecision<T> makeRetryDecision(Throwable error, T state, int attempt);
    }

    /** Builder for {@link ExtensionStepConfig}. */
    public static final class Builder<T> {
        private T initialState;
        private SerDes serDes;
        private RetryStrategy<T> retryStrategy;
        private StepSemantics semanticsPerRetry;

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

        /** Sets the retry strategy used when the extension function throws. */
        public Builder<T> retryStrategy(RetryStrategy<T> retryStrategy) {
            this.retryStrategy = retryStrategy;
            return this;
        }

        /** Sets the delivery semantics used for each attempt. */
        public Builder<T> semanticsPerRetry(StepSemantics semanticsPerRetry) {
            this.semanticsPerRetry = semanticsPerRetry;
            return this;
        }

        /** Builds the immutable configuration. */
        public ExtensionStepConfig<T> build() {
            return new ExtensionStepConfig<>(this);
        }
    }
}
