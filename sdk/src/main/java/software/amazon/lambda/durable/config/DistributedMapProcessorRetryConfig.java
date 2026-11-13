// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import java.time.Duration;

/**
 * Retry settings for a distributed map processor.
 *
 * <p>Retry policy belongs to the processor reference rather than the run, because it defines how the service invokes
 * that function. One Lambda function can back several processor references with different policies.
 */
public class DistributedMapProcessorRetryConfig {

    /** Sentinel for unbounded retry attempts (wire value -1). */
    public static final int UNLIMITED = -1;

    private static final long MIN_RETRY_DURATION_SECONDS = 60;
    private static final long MAX_RETRY_DURATION_SECONDS = 21600;

    private final Integer maxRetryAttempts;
    private final Duration maxRetryDuration;

    private DistributedMapProcessorRetryConfig(Builder builder) {
        this.maxRetryAttempts = builder.maxRetryAttempts;
        this.maxRetryDuration = builder.maxRetryDuration;
    }

    /** Returns the max retry attempts, UNLIMITED for unbounded, or null for the service default of 2. */
    public Integer maxRetryAttempts() {
        return maxRetryAttempts;
    }

    /** Returns the cumulative retry duration budget, or null for the service default of 6 hours. */
    public Duration maxRetryDuration() {
        return maxRetryDuration;
    }

    /**
     * Creates a new builder for {@code DistributedMapProcessorRetryConfig}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for DistributedMapProcessorRetryConfig. */
    public static class Builder {
        private Integer maxRetryAttempts;
        private Duration maxRetryDuration;

        private Builder() {}

        /**
         * Sets the number of function-error retries before an item permanently fails. Pass {@link #UNLIMITED} for no
         * attempt limit within the duration budget, or 0 to disable retries.
         *
         * @param maxRetryAttempts a non-negative count, or UNLIMITED
         * @return this builder
         */
        public Builder maxRetryAttempts(int maxRetryAttempts) {
            if (maxRetryAttempts < 0 && maxRetryAttempts != UNLIMITED) {
                throw new IllegalArgumentException(
                        "maxRetryAttempts must be non-negative or DistributedMapProcessorRetryConfig.UNLIMITED, got: "
                                + maxRetryAttempts);
            }
            this.maxRetryAttempts = maxRetryAttempts;
            return this;
        }

        /**
         * Sets the cumulative backoff budget across all of an item's retries, from 1 minute through 6 hours.
         *
         * @param maxRetryDuration the budget
         * @return this builder
         */
        public Builder maxRetryDuration(Duration maxRetryDuration) {
            if (maxRetryDuration != null
                    && (maxRetryDuration.toSeconds() < MIN_RETRY_DURATION_SECONDS
                            || maxRetryDuration.toSeconds() > MAX_RETRY_DURATION_SECONDS)) {
                throw new IllegalArgumentException("maxRetryDuration must be between 1 minute and 6 hours, got: "
                        + maxRetryDuration.toSeconds() + "s");
            }
            this.maxRetryDuration = maxRetryDuration;
            return this;
        }

        public DistributedMapProcessorRetryConfig build() {
            return new DistributedMapProcessorRetryConfig(this);
        }
    }
}
