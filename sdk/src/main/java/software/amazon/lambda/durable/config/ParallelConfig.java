// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

/**
 * Configuration options for parallel operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring concurrency limits and completion semantics for parallel
 * branch execution.
 */
public class ParallelConfig {
    private final int maxConcurrency;
    private final CompletionConfig completionConfig;

    private ParallelConfig(Builder builder) {
        this.maxConcurrency = builder.maxConcurrency == null ? Integer.MAX_VALUE : builder.maxConcurrency;
        this.completionConfig =
                builder.completionConfig == null ? CompletionConfig.allCompleted() : builder.completionConfig;
    }

    /** @return the maximum number of branches running simultaneously, or -1 for unlimited */
    public int maxConcurrency() {
        return maxConcurrency;
    }

    public CompletionConfig completionConfig() {
        return completionConfig;
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
        private Integer maxConcurrency;
        private CompletionConfig completionConfig;

        private Builder() {}

        /**
         * Sets the maximum number of branches that can run simultaneously.
         *
         * @param maxConcurrency the concurrency limit (default: unlimited)
         * @return this builder for method chaining
         */
        public Builder maxConcurrency(Integer maxConcurrency) {
            if (maxConcurrency != null && maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be at least 1, got: " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Sets the maximum number of branches that can run simultaneously.
         *
         * @param completionConfig the completion configuration for the parallel operation
         * @return this builder for method chaining
         */
        public Builder completionConfig(CompletionConfig completionConfig) {
            if (completionConfig != null && completionConfig.toleratedFailurePercentage() != null) {
                throw new IllegalArgumentException("ParallelConfig does not support toleratedFailurePercentage");
            }
            this.completionConfig = completionConfig;
            return this;
        }

        /**
         * Builds the ParallelConfig instance.
         *
         * @return a new ParallelConfig with the configured options
         * @throws IllegalArgumentException if any configuration values are invalid
         */
        public ParallelConfig build() {
            return new ParallelConfig(this);
        }
    }
}
