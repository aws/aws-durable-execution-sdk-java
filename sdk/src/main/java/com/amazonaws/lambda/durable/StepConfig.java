// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.retry.RetryStrategy;
import com.amazonaws.lambda.durable.serde.SerDes;

/**
 * Configuration options for step operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring various aspects of step execution, including retry behavior
 * and delivery semantics.
 */
public class StepConfig {
    private final RetryStrategy retryStrategy;
    private final StepSemantics semantics;
    private final SerDes serDes;

    private StepConfig(Builder builder) {
        this.retryStrategy = builder.retryStrategy;
        this.semantics = builder.semantics;
        this.serDes = builder.serDes;
    }

    /** @return the retry strategy for this step, or NO_RETRY if not specified */
    public RetryStrategy retryStrategy() {
        return retryStrategy != null ? retryStrategy : RetryStrategies.Presets.DEFAULT;
    }

    /** @return the delivery semantics for this step, defaults to AT_LEAST_ONCE_PER_RETRY if not specified */
    public StepSemantics semantics() {
        return semantics != null ? semantics : StepSemantics.AT_LEAST_ONCE_PER_RETRY;
    }

    /** @return the custom serializer for this step, or null if not specified (uses default SerDes) */
    public SerDes serDes() {
        return serDes;
    }

    public Builder toBuilder() {
        return new Builder(retryStrategy, semantics, serDes);
    }

    /**
     * Creates a new builder for StepConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder(null, null, null);
    }

    /** Builder for creating StepConfig instances. */
    public static class Builder {
        private RetryStrategy retryStrategy;
        private StepSemantics semantics;
        private SerDes serDes;

        private Builder(RetryStrategy retryStrategy, StepSemantics semantics, SerDes serDes) {
            this.retryStrategy = retryStrategy;
            this.semantics = semantics;
            this.serDes = serDes;
        }

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
         * Sets a custom serializer for the step.
         *
         * <p>If not specified, the step will use the default SerDes configured for the handler. This allows per-step
         * customization of serialization behavior, useful for steps that need special handling (e.g., custom date
         * formats, encryption, compression).
         *
         * @param serDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
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
