// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.retry.RetryStrategy;

/**
 * Configuration options for step operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring various aspects of step execution, including retry behavior.
 * Additional configuration options will be added in future versions.
 */
public class StepConfig {
    private final RetryStrategy retryStrategy;
    // TODO: Add other configuration options like SerDes, timeout, etc.

    private StepConfig(Builder builder) {
        this.retryStrategy = builder.retryStrategy;
    }

    /** @return the retry strategy for this step, or null if not specified */
    public RetryStrategy retryStrategy() {
        return retryStrategy;
    }

    /**
     * Creates a new builder for StepConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for creating StepConfig instances. */
    public static class Builder {
        private RetryStrategy retryStrategy;

        /**
         * Sets the retry strategy for the step.
         *
         * @param retryStrategy the retry strategy to use, or null for default behavior
         * @return this builder for method chaining
         */
        public Builder retryStrategy(RetryStrategy retryStrategy) {
            this.retryStrategy = retryStrategy;
            return this;
        }

        /**
         * Builds the StepConfig instance.
         *
         * @return a new StepConfig with the configured options
         */
        public StepConfig build() {
            return new StepConfig(this);
        }
    }
}
