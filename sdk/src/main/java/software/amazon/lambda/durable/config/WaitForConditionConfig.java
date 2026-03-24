// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.retry.WaitForConditionWaitStrategy;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for {@code waitForCondition} operations.
 *
 * <p>Holds only optional parameters for a waitForCondition call. Use {@link #builder()} to create instances.
 *
 * @param <T> the type of state being polled
 */
public class WaitForConditionConfig<T> {
    private final WaitForConditionWaitStrategy<T> waitStrategy;
    private final SerDes serDes;
    private final T initialState;

    private WaitForConditionConfig(Builder<T> builder) {
        this.waitStrategy = builder.waitStrategy;
        this.serDes = builder.serDes;
        this.initialState = builder.initialState;
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

    /** Returns the initial state object, or null if not specified. */
    public T initialState() {
        return initialState;
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
        b.initialState = this.initialState;
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
        private T initialState;

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

        /**
         * Sets the initial state for the waitForCondition operation. The initial state will be null if it's not set.
         *
         * @param initialState the initial state object to pass to the condition function
         * @return this builder for method chaining
         */
        public Builder<T> initialState(T initialState) {
            this.initialState = initialState;
            return this;
        }

        public WaitForConditionConfig<T> build() {
            return new WaitForConditionConfig<>(this);
        }
    }
}
