// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.time.Duration;
import java.util.function.Predicate;
import software.amazon.lambda.durable.exception.WaitForConditionException;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.validation.ParameterValidator;

/**
 * Factory class for creating common {@link WaitForConditionWaitStrategy} implementations.
 *
 * <p>Provides a builder for creating strategies with exponential backoff, jitter, and max attempts. Default values:
 * maxAttempts=60, initialDelay=5s, maxDelay=300s, backoffRate=1.5, jitter=FULL.
 */
public final class WaitStrategies {

    private WaitStrategies() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a builder for a wait strategy that uses a predicate to determine when to stop polling.
     *
     * @param shouldContinuePolling predicate that returns true if polling should continue
     * @param <T> the type of state being polled
     * @return a new builder
     */
    public static <T> Builder<T> builder(Predicate<T> shouldContinuePolling) {
        if (shouldContinuePolling == null) {
            throw new IllegalArgumentException("shouldContinuePolling cannot be null");
        }
        return new Builder<>(shouldContinuePolling);
    }

    public static class Builder<T> {
        private final Predicate<T> shouldContinuePolling; // Function to determine if polling should continue
        private int maxAttempts = 60; // Maximum number of attempts
        private Duration initialDelay = Duration.ofSeconds(5); // Initial delay before first retry
        private Duration maxDelay = Duration.ofSeconds(300); // Maximum delay between retries
        private double backoffRate = 1.5; // Multiplier for each subsequent retry
        private JitterStrategy jitter = JitterStrategy.FULL; // Jitter strategy to apply to retry delays

        private Builder(Predicate<T> shouldContinuePolling) {
            this.shouldContinuePolling = shouldContinuePolling;
        }

        public Builder<T> maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder<T> initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder<T> maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder<T> backoffRate(double backoffRate) {
            this.backoffRate = backoffRate;
            return this;
        }

        public Builder<T> jitter(JitterStrategy jitter) {
            this.jitter = jitter;
            return this;
        }

        /**
         * Builds the wait strategy.
         *
         * @return a {@link WaitForConditionWaitStrategy} implementing exponential backoff with jitter
         */
        public WaitForConditionWaitStrategy<T> build() {
            ParameterValidator.validatePositiveInteger(maxAttempts, "maxAttempts");
            ParameterValidator.validateDuration(initialDelay, "initialDelay");
            ParameterValidator.validateDuration(maxDelay, "maxDelay");
            if (backoffRate < 1.0) {
                throw new IllegalArgumentException("backoffRate must be >= 1.0, got: " + backoffRate);
            }
            if (jitter == null) {
                throw new IllegalArgumentException("jitter cannot be null");
            }

            return (state, attempt) -> {
                // Check if condition is met
                if (!shouldContinuePolling.test(state)) {
                    return WaitForConditionDecision.stopPolling();
                }

                // Check if we've exceeded max attempts
                if (attempt + 1 >= maxAttempts) {
                    throw new WaitForConditionException(
                            "waitForCondition exceeded maximum attempts (" + maxAttempts + ")");
                }

                // Calculate delay with exponential backoff
                var initialDelaySeconds = initialDelay.toSeconds();
                var maxDelaySeconds = maxDelay.toSeconds();

                double baseDelay = Math.min(initialDelaySeconds * Math.pow(backoffRate, attempt), maxDelaySeconds);

                // Apply jitter
                double delayWithJitter =
                        switch (jitter) {
                            case NONE -> baseDelay;
                            case FULL -> Math.random() * baseDelay; // Random between 0 and delay
                            case HALF ->
                                baseDelay / 2 + Math.random() * (baseDelay / 2); // Random between delay/2 and delay
                        };

                // Ensure delay is an integer >= 1
                long finalDelaySeconds = Math.max(1, Math.round(delayWithJitter));

                return WaitForConditionDecision.continuePolling(Duration.ofSeconds(finalDelaySeconds));
            };
        }
    }
}
