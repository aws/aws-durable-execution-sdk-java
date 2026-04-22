// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.retry.RetryStrategy;

/**
 * Configuration for {@link software.amazon.lambda.durable.util.RetryOperationHelper#retryOperation}.
 *
 * <p>Uses the same {@link RetryStrategy} shape that developers already know from {@link StepConfig}, so there are zero
 * new retry concepts to learn.
 */
public class RetryOperationConfig {
    private final RetryStrategy retryStrategy;
    private final boolean wrapInChildContext;

    private RetryOperationConfig(Builder builder) {
        this.retryStrategy = builder.retryStrategy;
        this.wrapInChildContext = builder.wrapInChildContext;
    }

    /**
     * Returns the retry strategy. Same type as {@link StepConfig#retryStrategy()}.
     *
     * @return the retry strategy, never null
     */
    public RetryStrategy retryStrategy() {
        return retryStrategy;
    }

    /**
     * Whether to wrap the retry loop in {@code runInChildContext} so all attempts are grouped under a single named
     * operation in execution history. Only applies when a name is provided to the named form of {@code retryOperation}.
     * Defaults to {@code true}.
     *
     * @return true if child-context wrapping is enabled
     */
    public boolean wrapInChildContext() {
        return wrapInChildContext;
    }

    /**
     * Creates a new builder for {@code RetryOperationConfig}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for creating {@link RetryOperationConfig} instances. */
    public static class Builder {
        private RetryStrategy retryStrategy;
        private boolean wrapInChildContext = true;

        private Builder() {}

        /**
         * Sets the retry strategy. Required.
         *
         * <p>Reuses the exact same {@link RetryStrategy} interface from {@link StepConfig}. All existing factory
         * methods ({@link software.amazon.lambda.durable.retry.RetryStrategies#exponentialBackoff},
         * {@link software.amazon.lambda.durable.retry.RetryStrategies#fixedDelay}, presets, and custom lambdas) work
         * without modification.
         *
         * @param retryStrategy the retry strategy to use
         * @return this builder for method chaining
         */
        public Builder retryStrategy(RetryStrategy retryStrategy) {
            this.retryStrategy = retryStrategy;
            return this;
        }

        /**
         * Controls whether the retry loop is wrapped in a child context. Only meaningful for the named form of
         * {@code retryOperation}. Defaults to {@code true}.
         *
         * <p>When {@code true}, all attempts and backoff waits are grouped under a single named operation in execution
         * history, providing a cleaner view and isolated operation ID space. Set to {@code false} to flatten attempts
         * into the parent context.
         *
         * @param wrapInChildContext whether to wrap in a child context
         * @return this builder for method chaining
         */
        public Builder wrapInChildContext(boolean wrapInChildContext) {
            this.wrapInChildContext = wrapInChildContext;
            return this;
        }

        /**
         * Builds the {@link RetryOperationConfig} instance.
         *
         * @return a new config with the configured options
         * @throws IllegalArgumentException if retryStrategy is not set
         */
        public RetryOperationConfig build() {
            if (retryStrategy == null) {
                throw new IllegalArgumentException("retryStrategy is required");
            }
            return new RetryOperationConfig(this);
        }
    }
}
