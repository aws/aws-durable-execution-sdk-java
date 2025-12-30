// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.retry.RetryStrategy;

/**
 * Configuration options for step operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring various aspects of step execution, including retry behavior
 * and delivery semantics.
 */
public class StepConfig {
    private final RetryStrategy retryStrategy;
    private final StepSemantics semantics;

    private StepConfig(Builder builder) {
        this.retryStrategy = builder.retryStrategy;
        this.semantics = builder.semantics;
    }

    /** @return the retry strategy for this step, or null if not specified */
    public RetryStrategy retryStrategy() {
        return retryStrategy;
    }

    /** @return the delivery semantics for this step, defaults to AT_LEAST_ONCE_PER_RETRY if not specified */
    public StepSemantics semantics() {
        return semantics != null ? semantics : StepSemantics.AT_LEAST_ONCE_PER_RETRY;
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
        private StepSemantics semantics;

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
         * Sets the delivery semantics for the step.
         *
         * @param semantics the delivery semantics to use, defaults to AT_LEAST_ONCE_PER_RETRY if not specified
         * @return this builder for method chaining
         */
        public Builder semantics(StepSemantics semantics) {
            this.semantics = semantics;
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
