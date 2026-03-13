// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.retry;

/**
 * Jitter strategy for retry delays to prevent thundering herd problems.
 *
 * <p>Jitter reduces simultaneous retry attempts by spreading retries out over a randomized delay interval, which helps
 * prevent overwhelming services when many clients retry at the same time.
 */
public enum JitterStrategy {

    /**
     * No jitter - use exact calculated delay. This provides predictable timing but may cause thundering herd issues.
     */
    NONE {
        @Override
        public double apply(double baseDelay) {
            return baseDelay;
        }
    },
    /**
     * Full jitter - random delay between 0 and calculated delay. This provides maximum spread but may result in very
     * short delays.
     */
    FULL {
        @Override
        public double apply(double baseDelay) {
            return Math.random() * baseDelay;
        }
    },
    /**
     * Half jitter - random delay between 50% and 100% of calculated delay. This provides good spread while maintaining
     * reasonable minimum delays.
     */
    HALF {
        @Override
        public double apply(double baseDelay) {
            return baseDelay / 2 + Math.random() * (baseDelay / 2);
        }
    };

    /**
     * Applies jitter to the given base delay.
     *
     * @param baseDelay the calculated delay before jitter
     * @return the delay after applying jitter
     */
    public abstract double apply(double baseDelay);
}
