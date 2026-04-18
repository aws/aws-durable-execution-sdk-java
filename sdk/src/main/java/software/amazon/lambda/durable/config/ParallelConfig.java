// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import java.util.Objects;

/**
 * Configuration options for parallel operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring concurrency limits and completion semantics for parallel
 * branch execution.
 */
public class ParallelConfig {
    private final int maxConcurrency;
    private final CompletionConfig completionConfig;
    private final NestingType nestingType;

    private ParallelConfig(Builder builder) {
        this.maxConcurrency = Objects.requireNonNullElse(builder.maxConcurrency, Integer.MAX_VALUE);
        this.completionConfig = Objects.requireNonNullElseGet(builder.completionConfig, CompletionConfig::allCompleted);
        this.nestingType = Objects.requireNonNullElse(builder.nestingType, NestingType.NESTED);
    }

    /** @return the maximum number of branches running simultaneously, or -1 for unlimited */
    public int maxConcurrency() {
        return maxConcurrency;
    }

    /** @return the completion configuration for the parallel operation */
    public CompletionConfig completionConfig() {
        return completionConfig;
    }

    /** @return the nesting type for the parallel operation */
    public NestingType nestingType() {
        return nestingType;
    }

    /**
     * Creates a new builder for ParallelConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .maxConcurrency(maxConcurrency)
                .completionConfig(completionConfig)
                .nestingType(nestingType);
    }

    /** Builder for creating ParallelConfig instances. */
    public static class Builder {
        private Integer maxConcurrency;
        private CompletionConfig completionConfig;
        private NestingType nestingType;

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
         * Sets the nesting type for the parallel operation.
         *
         * @param nestingType the nesting type (default: {@link NestingType#NESTED})
         * @return this builder for method chaining
         */
        public Builder nestingType(NestingType nestingType) {
            this.nestingType = nestingType;
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
