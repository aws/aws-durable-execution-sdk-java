// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import java.time.Duration;
import software.amazon.lambda.durable.serde.SerDes;

/** Optional configuration for a distributed map run. */
public class DistributedMapConfig {
    private static final long MAX_TIMEOUT_SECONDS = 7776000; // 90 days

    private final DistributedMapDestination destination;
    private final CompletionConfig completionConfig;
    private final Duration timeout;
    private final SerDes resultSerDes;

    private DistributedMapConfig(Builder builder) {
        if (builder.timeout != null
                && (builder.timeout.toSeconds() <= 0 || builder.timeout.toSeconds() > MAX_TIMEOUT_SECONDS)) {
            throw new IllegalArgumentException(
                    "timeout must be positive and at most 90 days, got: " + builder.timeout.toSeconds() + "s");
        }
        this.destination = builder.destination;
        this.completionConfig = builder.completionConfig;
        this.timeout = builder.timeout;
        this.resultSerDes = builder.resultSerDes;
    }

    public DistributedMapDestination destination() {
        return destination;
    }

    public CompletionConfig completionConfig() {
        return completionConfig;
    }

    public Duration timeout() {
        return timeout;
    }

    public SerDes resultSerDes() {
        return resultSerDes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        var builder = new Builder();
        builder.destination = destination;
        builder.completionConfig = completionConfig;
        builder.timeout = timeout;
        builder.resultSerDes = resultSerDes;
        return builder;
    }

    /** Builder for DistributedMapConfig. */
    public static class Builder {
        private DistributedMapDestination destination;
        private CompletionConfig completionConfig;
        private Duration timeout;
        private SerDes resultSerDes;

        private Builder() {}

        public Builder destination(DistributedMapDestination destination) {
            this.destination = destination;
            return this;
        }

        public Builder completionConfig(CompletionConfig completionConfig) {
            this.completionConfig = completionConfig;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder resultSerDes(SerDes resultSerDes) {
            this.resultSerDes = resultSerDes;
            return this;
        }

        public DistributedMapConfig build() {
            return new DistributedMapConfig(this);
        }
    }

    /**
     * Failure-tolerance configuration for a distributed map run. {@code toleratedFailurePercentage} is a number from 0
     * through 100 and may be fractional.
     */
    public record CompletionConfig(
            Integer toleratedFailureCount, Double toleratedFailurePercentage, Integer minimumSampleSize) {

        public CompletionConfig {
            if (toleratedFailureCount != null && toleratedFailurePercentage != null) {
                throw new IllegalArgumentException(
                        "toleratedFailureCount and toleratedFailurePercentage are mutually exclusive");
            }
            if (minimumSampleSize != null && toleratedFailurePercentage == null) {
                throw new IllegalArgumentException("minimumSampleSize is only valid with toleratedFailurePercentage");
            }
            // Omitting the config means zero tolerated failures, so an empty one is rejected rather than omitted.
            if (toleratedFailureCount == null && toleratedFailurePercentage == null) {
                throw new IllegalArgumentException(
                        "a completion config must set toleratedFailureCount or toleratedFailurePercentage");
            }
            if (toleratedFailureCount != null && toleratedFailureCount < 0) {
                throw new IllegalArgumentException(
                        "toleratedFailureCount must be non-negative, got: " + toleratedFailureCount);
            }
            if (toleratedFailurePercentage != null && !Double.isFinite(toleratedFailurePercentage)) {
                throw new IllegalArgumentException(
                        "toleratedFailurePercentage must be a finite number, got: " + toleratedFailurePercentage);
            }
            if (toleratedFailurePercentage != null
                    && (toleratedFailurePercentage < 0 || toleratedFailurePercentage > 100)) {
                throw new IllegalArgumentException(
                        "toleratedFailurePercentage must be between 0 and 100, got: " + toleratedFailurePercentage);
            }
            if (minimumSampleSize != null && minimumSampleSize < 1) {
                throw new IllegalArgumentException("minimumSampleSize must be at least 1, got: " + minimumSampleSize);
            }
        }

        /** Stops the run after more than this many items permanently fail. */
        public static CompletionConfig failureCount(int count) {
            return new CompletionConfig(count, null, null);
        }

        /** Abort once the failure rate exceeds this percentage, a number from 0 through 100 that may be fractional. */
        public static CompletionConfig failurePercentage(double percentage) {
            return new CompletionConfig(null, percentage, null);
        }

        /**
         * Abort once the failure rate exceeds this percentage, a number from 0 through 100 that may be fractional,
         * gated by a minimum sample size.
         */
        public static CompletionConfig failurePercentage(double percentage, int minimumSampleSize) {
            return new CompletionConfig(null, percentage, minimumSampleSize);
        }
    }
}
