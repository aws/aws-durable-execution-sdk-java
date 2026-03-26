// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Factory class for creating common polling strategies. */
public class PollingStrategies {

    /** Preset polling strategies for common use cases. */
    public static class Presets {

        /**
         * Default polling strategy: - Base interval: 1 second - Backoff rate: 2x - Jitter: FULL - Max interval 10
         * second
         */
        public static final PollingStrategy DEFAULT =
                exponentialBackoff(Duration.ofMillis(1000), 2.0, JitterStrategy.FULL, Duration.ofSeconds(10));
    }

    /**
     * Creates an exponential backoff polling strategy.
     *
     * <p>The delay calculation follows the formula: delay = jitter(baseInterval × backoffRate^(attempt-1))
     *
     * @param baseInterval Base delay before first poll
     * @param backoffRate Multiplier for exponential backoff (must be positive)
     * @param jitter Jitter strategy to apply to delays
     * @param maxInterval Maximum delay between polls
     * @return PollingStrategy implementing exponential backoff with jitter
     */
    public static PollingStrategy exponentialBackoff(
            Duration baseInterval, double backoffRate, JitterStrategy jitter, Duration maxInterval) {
        Objects.requireNonNull(jitter, "jitter must not be null");
        Objects.requireNonNull(baseInterval, "base interval must not be null");
        Objects.requireNonNull(maxInterval, "max interval must not be null");
        if (backoffRate <= 0) {
            throw new IllegalArgumentException("backoffRate must be positive");
        }

        if (baseInterval.isNegative() || baseInterval.isZero()) {
            throw new IllegalArgumentException("baseInterval must be positive");
        }

        if (maxInterval.isNegative() || maxInterval.isZero()) {
            throw new IllegalArgumentException("maxInterval must be positive");
        }

        return (attempt) -> {
            // attempt is 1-based
            double delayMs = baseInterval.toMillis() * Math.pow(backoffRate, attempt - 1);
            delayMs = Math.min(jitter.apply(delayMs), maxInterval.toMillis());
            return Duration.ofMillis(Math.round(delayMs));
        };
    }

    /**
     * Creates a fixed-delay polling strategy that uses the same interval for every attempt.
     *
     * @param interval Fixed delay between polls
     * @return PollingStrategy with fixed delay
     */
    public static PollingStrategy fixedDelay(Duration interval) {
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return (attempt) -> interval;
    }

    /**
     * Creates a polling strategy that polls at a specific instant in time.
     *
     * @param instant The instant to poll at
     * @return PollingStrategy that calculates delay until the specified instant
     */
    public static PollingStrategy at(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return (attempt) -> {
            var duration = Duration.between(Instant.now(), instant);
            if (duration.isNegative()) {
                // as soon as possible
                return Duration.ZERO;
            }
            return duration;
        };
    }
}
