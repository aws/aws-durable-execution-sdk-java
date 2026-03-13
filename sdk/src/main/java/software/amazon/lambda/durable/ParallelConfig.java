// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * Configuration options for parallel operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring concurrency limits and completion semantics for parallel
 * branch execution.
 */
public class ParallelConfig {
    private final int maxConcurrency;
    private final int minSuccessful;
    private final int toleratedFailureCount;

    private ParallelConfig(Builder builder) {
        this.maxConcurrency = builder.maxConcurrency;
        this.minSuccessful = builder.minSuccessful;
        this.toleratedFailureCount = builder.toleratedFailureCount;
    }

    /** @return the maximum number of branches running simultaneously, or -1 for unlimited */
    public int maxConcurrency() {
        return maxConcurrency;
    }

    /** @return the minimum number of successful branches required, or -1 meaning all must succeed */
    public int minSuccessful() {
        return minSuccessful;
    }

    /** @return the maximum number of branch failures tolerated before stopping */
    public int toleratedFailureCount() {
        return toleratedFailureCount;
    }

    /**
     * Creates a new builder for ParallelConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for creating ParallelConfig instances. */
    public static class Builder {
        private int maxConcurrency = -1;
        private int minSuccessful = -1;
        private int toleratedFailureCount = 0;

        private Builder() {}

        /**
         * Sets the maximum number of branches that can run simultaneously.
         *
         * @param maxConcurrency the concurrency limit, or -1 for unlimited
         * @return this builder for method chaining
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Sets the minimum number of branches that must succeed for the parallel operation to complete successfully.
         *
         * @param minSuccessful the minimum successful count, or -1 meaning all branches must succeed
         * @return this builder for method chaining
         */
        public Builder minSuccessful(int minSuccessful) {
            this.minSuccessful = minSuccessful;
            return this;
        }

        /**
         * Sets the maximum number of branch failures tolerated before stopping execution.
         *
         * @param toleratedFailureCount the maximum tolerated failures
         * @return this builder for method chaining
         */
        public Builder toleratedFailureCount(int toleratedFailureCount) {
            this.toleratedFailureCount = toleratedFailureCount;
            return this;
        }

        /**
         * Builds the ParallelConfig instance.
         *
         * @return a new ParallelConfig with the configured options
         * @throws IllegalArgumentException if any configuration values are invalid
         */
        public ParallelConfig build() {
            if (maxConcurrency != -1 && maxConcurrency <= 0) {
                throw new IllegalArgumentException(
                        "maxConcurrency must be -1 (unlimited) or greater than 0, got: " + maxConcurrency);
            }
            if (minSuccessful < -1) {
                throw new IllegalArgumentException("minSuccessful must be >= -1, got: " + minSuccessful);
            }
            if (toleratedFailureCount < 0) {
                throw new IllegalArgumentException("toleratedFailureCount must be >= 0, got: " + toleratedFailureCount);
            }
            return new ParallelConfig(this);
        }
    }
}
