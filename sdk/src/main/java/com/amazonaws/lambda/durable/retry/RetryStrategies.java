// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.retry;

import com.amazonaws.lambda.durable.validation.ParameterValidator;
import java.time.Duration;

/**
 * Factory class for creating common retry strategies.
 *
 * <p>This class provides preset retry strategies for common use cases, as well as factory methods for creating custom
 * retry strategies with exponential backoff and jitter.
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

        /** No retry strategy - fails immediately on first error. Use this for operations that should not be retried. */
        public static final RetryStrategy NO_RETRY = (error, attemptNumber) -> RetryDecision.fail();
    }

    /**
     * Creates an exponential backoff retry strategy.
     *
     * <p>The delay calculation follows the formula: baseDelay = min(initialDelay × backoffRate^attemptNumber, maxDelay)
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

        return (error, attemptNumber) -> {
            // Check if we've exceeded max attempts (attemptNumber is 0-based)
            if (attemptNumber + 1 >= maxAttempts) {
                return RetryDecision.fail();
            }

            // Calculate delay with exponential backoff
            double initialDelaySeconds = initialDelay.toSeconds();
            double maxDelaySeconds = maxDelay.toSeconds();

            double baseDelay = Math.min(initialDelaySeconds * Math.pow(backoffRate, attemptNumber), maxDelaySeconds);

            // Apply jitter
            double delayWithJitter =
                    switch (jitter) {
                        case NONE -> baseDelay;
                        case FULL -> Math.random() * baseDelay;
                        case HALF -> baseDelay / 2 + Math.random() * (baseDelay / 2);
                    };

            // Round to nearest second, minimum 1
            // Same rounding logic as TS SDK: https://tinyurl.com/4ntxsefu
            long finalDelaySeconds = Math.max(1, Math.round(delayWithJitter));

            return RetryDecision.retry(Duration.ofSeconds(finalDelaySeconds));
        };
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

        return (error, attemptNumber) -> {
            if (attemptNumber + 1 >= maxAttempts) {
                return RetryDecision.fail();
            }
            return RetryDecision.retry(fixedDelay);
        };
    }
}
