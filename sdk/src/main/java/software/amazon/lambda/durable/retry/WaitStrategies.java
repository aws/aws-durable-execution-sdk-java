// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import java.time.Duration;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;
import software.amazon.lambda.durable.util.ParameterValidator;

/**
 * Factory class for creating common {@link WaitForConditionWaitStrategy} implementations.
 *
 * <p>This class provides preset wait strategies (for use with waitForCondition) for common use cases, as well as
 * factory methods for creating custom retry strategies with exponential backoff and jitter.
 */
public final class WaitStrategies {

    /** Preset wait strategies for common use cases. */
    public static class Presets {

        /**
         * Default wait strategy: exponential backoff with 60 max attempts, 5s initial delay, 300s max delay, 1.5x
         * backoff rate, and FULL jitter.
         */
        @SuppressWarnings("rawtypes")
        public static final WaitForConditionWaitStrategy DEFAULT =
                exponentialBackoff(60, Duration.ofSeconds(5), Duration.ofSeconds(300), 1.5, JitterStrategy.FULL);
    }

    /**
     * Returns the default wait strategy.
     *
     * @param <T> the type of state being polled
     * @return the default wait strategy
     */
    @SuppressWarnings("unchecked")
    public static <T> WaitForConditionWaitStrategy<T> defaultStrategy() {
        return Presets.DEFAULT;
    }

    /**
     * Creates an exponential backoff wait strategy.
     *
     * <p>The delay calculation follows the formula: baseDelay = min(initialDelay × backoffRate^attempt, maxDelay)
     *
     * @param maxAttempts maximum number of attempts before throwing {@link WaitForConditionFailedException}
     * @param initialDelay initial delay before first retry
     * @param maxDelay maximum delay between retries
     * @param backoffRate multiplier for exponential backoff (must be >= 1.0)
     * @param jitter jitter strategy to apply to delays
     * @param <T> the type of state being polled
     * @return a {@link WaitForConditionWaitStrategy} implementing exponential backoff with jitter
     */
    public static <T> WaitForConditionWaitStrategy<T> exponentialBackoff(
            int maxAttempts, Duration initialDelay, Duration maxDelay, double backoffRate, JitterStrategy jitter) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive, got: " + maxAttempts);
        }
        ParameterValidator.validateDuration(initialDelay, "initialDelay");
        ParameterValidator.validateDuration(maxDelay, "maxDelay");
        if (backoffRate < 1.0) {
            throw new IllegalArgumentException("backoffRate must be >= 1.0, got: " + backoffRate);
        }
        if (jitter == null) {
            throw new IllegalArgumentException("jitter cannot be null");
        }

        return (state, attempt) -> {
            if (attempt + 1 >= maxAttempts) {
                throw new WaitForConditionFailedException(
                        "waitForCondition exceeded maximum attempts (" + maxAttempts + ")");
            }

            double initialDelaySeconds = initialDelay.toSeconds();
            double maxDelaySeconds = maxDelay.toSeconds();
            double baseDelay = Math.min(initialDelaySeconds * Math.pow(backoffRate, attempt), maxDelaySeconds);
            double delayWithJitter = jitter.apply(baseDelay);
            long finalDelaySeconds = Math.max(1, Math.round(delayWithJitter));

            return Duration.ofSeconds(finalDelaySeconds);
        };
    }

    /**
     * Creates a fixed delay wait strategy that returns a constant delay regardless of attempt number or state.
     *
     * @param maxAttempts maximum number of attempts before throwing {@link WaitForConditionFailedException}
     * @param fixedDelay the constant delay between polling attempts
     * @param <T> the type of state being polled
     * @return a {@link WaitForConditionWaitStrategy} with fixed delay
     */
    public static <T> WaitForConditionWaitStrategy<T> fixedDelay(int maxAttempts, Duration fixedDelay) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive, got: " + maxAttempts);
        }
        ParameterValidator.validateDuration(fixedDelay, "fixedDelay");

        return (state, attempt) -> {
            if (attempt + 1 >= maxAttempts) {
                throw new WaitForConditionFailedException(
                        "waitForCondition exceeded maximum attempts (" + maxAttempts + ")");
            }
            return fixedDelay;
        };
    }
}
