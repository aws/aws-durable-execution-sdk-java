// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.retry.WaitForConditionWaitStrategy;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for {@code waitForCondition} operations.
 *
 * <p>Holds only optional parameters for a waitForCondition call. Use {@link #builder()} to
 * create instances.
 *
 * @param <T> the type of state being polled
 */
public class WaitForConditionConfig<T> {
    private final WaitForConditionWaitStrategy<T> waitStrategy;
    private final SerDes serDes;

    private WaitForConditionConfig(Builder<T> builder) {
        this.waitStrategy = builder.waitStrategy;
        this.serDes = builder.serDes;
    }

    /**
     * Returns the wait strategy that controls polling behavior. If no strategy was explicitly set, returns the default
     * strategy from {@link WaitStrategies#defaultStrategy()}.
     */
    public WaitForConditionWaitStrategy<T> waitStrategy() {
        return waitStrategy != null ? waitStrategy : WaitStrategies.defaultStrategy();
    }

    /** Returns the custom serializer, or null if not specified (uses default SerDes). */
    public SerDes serDes() {
        return serDes;
    }

    /**
     * Returns a new builder initialized with the values from this config. Useful internally for injecting default
     * SerDes.
     *
     * @return a new builder pre-populated with this config's values
     */
    public Builder<T> toBuilder() {
        var b = new Builder<T>();
        b.waitStrategy = this.waitStrategy;
        b.serDes = this.serDes;
        return b;
    }

    /**
     * Creates a new builder for {@code WaitForConditionConfig}. All fields are optional.
     *
     * @param <T> the type of state being polled
     * @return a new builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private WaitForConditionWaitStrategy<T> waitStrategy;
        private SerDes serDes;

        private Builder() {}

        /**
         * Sets the wait strategy for the waitForCondition operation.
         *
         * <p>If not specified, the default exponential backoff strategy from {@link WaitStrategies#defaultStrategy()}
         * is used.
         *
         * @param waitStrategy the strategy controlling polling intervals and termination
         * @return this builder for method chaining
         */
        public Builder<T> waitStrategy(WaitForConditionWaitStrategy<T> waitStrategy) {
            this.waitStrategy = waitStrategy;
            return this;
        }

        /**
         * Sets a custom serializer for the waitForCondition operation.
         *
         * <p>If not specified, the operation will use the default SerDes configured for the handler.
         *
         * @param serDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder<T> serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        public WaitForConditionConfig<T> build() {
            return new WaitForConditionConfig<>(this);
        }
    }
}
