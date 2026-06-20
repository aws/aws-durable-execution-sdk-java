// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import java.time.Duration;
import software.amazon.lambda.durable.util.ParameterValidator;

/**
 * Factory class for creating common retry strategies.
 *
 * <p>This class provides preset retry strategies for common use cases, as well as factory methods for creating custom
 * retry strategies with exponential or linear backoff and jitter.
 */
public class RetryStrategies {

    /** Preset retry strategies for common use cases. */
    public static class Presets {

        /**
         * Default retry strategy: - 6 total attempts (1 initial + 5 retries) - Initial delay: 5 seconds - Max delay: 60
         * seconds - Backoff rate: 2x - Jitter: FULL
         */
        public static final RetryStrategy DEFAULT = exponentialBackoff(
                6, // maxAttempts
                Duration.ofSeconds(5), // initialDelay
                Duration.ofSeconds(60), // maxDelay
                2.0, // backoffRate
                JitterStrategy.FULL // jitter
                );

        /**
         * Linear retry strategy: - 6 total attempts (1 initial + 5 retries) - Initial delay: 1 second - Max delay: 5
         * seconds - Increment: 1 second - Jitter: NONE
         */
        public static final RetryStrategy LINEAR = linearBackoff(
                6, // maxAttempts
                Duration.ofSeconds(1), // initialDelay
                Duration.ofSeconds(5), // maxDelay
                Duration.ofSeconds(1), // increment
                JitterStrategy.NONE // jitter
                );

        /** No retry strategy - fails immediately on first error. Use this for operations that should not be retried. */
        public static final RetryStrategy NO_RETRY = (error, attempt) -> RetryDecision.fail();
    }

    /**
     * Creates an exponential backoff retry strategy.
     *
     * <p>The delay calculation follows the formula: baseDelay = min(initialDelay × backoffRate^(attempt-1), maxDelay)
     *
     * @param maxAttempts Maximum number of attempts (including initial attempt)
     * @param initialDelay Initial delay before first retry
     * @param maxDelay Maximum delay between retries
     * @param backoffRate Multiplier for exponential backoff
     * @param jitter Jitter strategy to apply to delays
     * @return RetryStrategy implementing exponential backoff with jitter
     */
    public static RetryStrategy exponentialBackoff(
            int maxAttempts, Duration initialDelay, Duration maxDelay, double backoffRate, JitterStrategy jitter) {

        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        ParameterValidator.validateDuration(initialDelay, "initialDelay");
        ParameterValidator.validateDuration(maxDelay, "maxDelay");
        if (backoffRate <= 0) {
            throw new IllegalArgumentException("backoffRate must be positive");
        }

        return (error, attempt) -> {
            // Check if we've exceeded max attempts (attemptNumber is 1-based)
            if (attempt >= maxAttempts) {
                return RetryDecision.fail();
            }

            // Calculate delay with exponential backoff
            double initialDelaySeconds = initialDelay.toSeconds();
            double maxDelaySeconds = maxDelay.toSeconds();

            double baseDelay = Math.min(initialDelaySeconds * Math.pow(backoffRate, attempt - 1), maxDelaySeconds);

            // Apply jitter
            double delayWithJitter = jitter.apply(baseDelay);

            // Round to nearest second, minimum 1
            // Same rounding logic as TS SDK: https://tinyurl.com/4ntxsefu
            long finalDelaySeconds = Math.max(1, Math.round(delayWithJitter));

            return RetryDecision.retry(Duration.ofSeconds(finalDelaySeconds));
        };
    }

    /**
     * Creates a linear backoff retry strategy.
     *
     * <p>The delay calculation follows the formula: delay = initialDelay + increment × (attempt-1)
     *
     * @param maxAttempts Maximum number of attempts (including initial attempt)
     * @param initialDelay Initial delay before first retry
     * @param increment Amount to add to the delay after each retry attempt
     * @return RetryStrategy implementing linear backoff
     */
    public static RetryStrategy linearBackoff(int maxAttempts, Duration initialDelay, Duration increment) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        ParameterValidator.validateDuration(initialDelay, "initialDelay");
        ParameterValidator.validateDuration(increment, "increment");

        return (error, attempt) -> {
            if (attempt >= maxAttempts) {
                return RetryDecision.fail();
            }

            var delay = initialDelay.plus(increment.multipliedBy(attempt - 1));
            return RetryDecision.retry(delay);
        };
    }

    /**
     * Creates a linear backoff retry strategy.
     *
     * <p>The delay calculation follows the formula: baseDelay = min(initialDelay + increment × (attempt-1), maxDelay)
     *
     * @param maxAttempts Maximum number of attempts (including initial attempt)
     * @param initialDelay Initial delay before first retry
     * @param maxDelay Maximum delay between retries
     * @param increment Amount to add to the delay after each retry attempt
     * @param jitter Jitter strategy to apply to delays
     * @return RetryStrategy implementing linear backoff with jitter
     */
    public static RetryStrategy linearBackoff(
            int maxAttempts, Duration initialDelay, Duration maxDelay, Duration increment, JitterStrategy jitter) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        ParameterValidator.validateDuration(initialDelay, "initialDelay");
        ParameterValidator.validateDuration(maxDelay, "maxDelay");
        ParameterValidator.validateDuration(increment, "increment");
        if (jitter == null) {
            throw new IllegalArgumentException("jitter cannot be null");
        }

        return (error, attempt) -> {
            if (attempt >= maxAttempts) {
                return RetryDecision.fail();
            }

            var baseDelay = calculateCappedLinearDelay(initialDelay, maxDelay, increment, attempt);
            if (jitter == JitterStrategy.NONE) {
                return RetryDecision.retry(baseDelay);
            }

            var delayWithJitter = jitter.apply(baseDelay.toSeconds());
            var finalDelaySeconds = Math.max(1, Math.round(delayWithJitter));

            return RetryDecision.retry(Duration.ofSeconds(finalDelaySeconds));
        };
    }

    private static Duration calculateCappedLinearDelay(
            Duration initialDelay, Duration maxDelay, Duration increment, int attempt) {
        if (initialDelay.compareTo(maxDelay) >= 0) {
            return maxDelay;
        }

        var increments = attempt - 1L;
        var remaining = maxDelay.minus(initialDelay);
        if (increments > remaining.dividedBy(increment)) {
            return maxDelay;
        }

        return initialDelay.plus(increment.multipliedBy(increments));
    }

    /**
     * Creates a simple retry strategy that retries a fixed number of times with a fixed delay.
     *
     * @param maxAttempts Maximum number of attempts (including initial attempt)
     * @param fixedDelay Fixed delay between retries
     * @return RetryStrategy with fixed delay
     */
    public static RetryStrategy fixedDelay(int maxAttempts, Duration fixedDelay) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        ParameterValidator.validateDuration(fixedDelay, "fixedDelay");

        return (error, attempt) -> {
            if (attempt >= maxAttempts) {
                return RetryDecision.fail();
            }
            return RetryDecision.retry(fixedDelay);
        };
    }
}
