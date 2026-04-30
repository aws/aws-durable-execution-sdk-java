// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.RetryStrategy;

/**
 * Configuration for {@link software.amazon.lambda.durable.DurableContext#withRetry}.
 *
 * <p>Uses the same {@link RetryStrategy} shape that developers already know from {@link StepConfig}, so there are zero
 * new retry concepts to learn. If no retry strategy is specified, {@link RetryStrategies.Presets#DEFAULT} is used.
 */
public class WithRetryConfig {
    private final RetryStrategy retryStrategy;
    private final boolean wrapInChildContext;

    private WithRetryConfig(Builder builder) {
        this.retryStrategy = builder.retryStrategy;
        this.wrapInChildContext = builder.wrapInChildContext;
    }

    /**
     * Returns the retry strategy, or the default strategy if not specified. Same type as
     * {@link StepConfig#retryStrategy()}.
     *
     * @return the retry strategy, never null
     */
    public RetryStrategy retryStrategy() {
        return retryStrategy != null ? retryStrategy : RetryStrategies.Presets.DEFAULT;
    }

    /**
     * Returns whether the sync {@code withRetry} should wrap the retry loop in a child context.
     *
     * <p>When {@code true}, the sync form behaves like the async form — all retry attempts are grouped under a single
     * named child context in execution history. When {@code false} (the default), the retry loop runs directly on the
     * caller's context.
     *
     * <p>This setting has no effect on the async {@code withRetryAsync} methods, which always wrap in a child context.
     *
     * @return {@code true} if the sync retry loop should be wrapped in a child context
     */
    public boolean wrapInChildContext() {
        return wrapInChildContext;
    }

    /**
     * Creates a new builder for {@code WithRetryConfig}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for creating {@link WithRetryConfig} instances. */
    public static class Builder {
        private RetryStrategy retryStrategy;
        private boolean wrapInChildContext;

        private Builder() {}

        /**
         * Sets the retry strategy. Optional — defaults to {@link RetryStrategies.Presets#DEFAULT} if not set.
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
         * Sets whether the sync {@code withRetry} should wrap the retry loop in a child context. Optional — defaults to
         * {@code false}.
         *
         * <p>When enabled, the sync form groups all retry attempts under a single named child context in execution
         * history, matching the behavior of the async form. This is useful when you want operation isolation but don't
         * need a {@link software.amazon.lambda.durable.DurableFuture}.
         *
         * @param wrapInChildContext {@code true} to wrap in a child context
         * @return this builder for method chaining
         */
        public Builder wrapInChildContext(boolean wrapInChildContext) {
            this.wrapInChildContext = wrapInChildContext;
            return this;
        }

        /**
         * Builds the {@link WithRetryConfig} instance.
         *
         * @return a new config with the configured options
         */
        public WithRetryConfig build() {
            return new WithRetryConfig(this);
        }
    }
}
