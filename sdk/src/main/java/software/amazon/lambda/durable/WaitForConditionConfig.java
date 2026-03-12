// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for {@code waitForCondition} operations.
 *
 * <p>Bundles the wait strategy, initial state, and optional SerDes for a waitForCondition call. Use
 * {@link #builder(WaitForConditionWaitStrategy, Object)} to create instances.
 *
 * @param <T> the type of state being polled
 */
public class WaitForConditionConfig<T> {
    private final WaitForConditionWaitStrategy<T> waitStrategy;
    private final T initialState;
    private final SerDes serDes;

    private WaitForConditionConfig(Builder<T> builder) {
        this.waitStrategy = builder.waitStrategy;
        this.initialState = builder.initialState;
        this.serDes = builder.serDes;
    }

    /** Returns the wait strategy that controls polling behavior. */
    public WaitForConditionWaitStrategy<T> waitStrategy() {
        return waitStrategy;
    }

    /** Returns the initial state passed to the first check function invocation. */
    public T initialState() {
        return initialState;
    }

    /** Returns the custom serializer, or null if not specified (uses default SerDes). */
    public SerDes serDes() {
        return serDes;
    }

    /**
     * Creates a new builder with the required wait strategy and initial state.
     *
     * @param waitStrategy the strategy controlling polling intervals and termination
     * @param initialState the initial state for the first check invocation
     * @param <T> the type of state being polled
     * @return a new builder instance
     * @throws IllegalArgumentException if waitStrategy or initialState is null
     */
    public static <T> Builder<T> builder(WaitForConditionWaitStrategy<T> waitStrategy, T initialState) {
        if (waitStrategy == null) {
            throw new IllegalArgumentException("waitStrategy must not be null");
        }
        if (initialState == null) {
            throw new IllegalArgumentException("initialState must not be null");
        }
        return new Builder<>(waitStrategy, initialState);
    }

    public static class Builder<T> {
        private final WaitForConditionWaitStrategy<T> waitStrategy;
        private final T initialState;
        private SerDes serDes;

        private Builder(WaitForConditionWaitStrategy<T> waitStrategy, T initialState) {
            this.waitStrategy = waitStrategy;
            this.initialState = initialState;
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
