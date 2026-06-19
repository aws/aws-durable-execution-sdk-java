// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.RetryStrategy;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration options for step operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring various aspects of step execution, including retry behavior
 * and delivery semantics.
 */
public class StepConfig {
    private final RetryStrategy retryStrategy;
    private final StepSemantics semanticsPerRetry;
    private final SerDes serDes;

    private StepConfig(Builder builder) {
        this.retryStrategy = builder.retryStrategy;
        this.semanticsPerRetry = builder.semanticsPerRetry;
        this.serDes = builder.serDes;
    }

    /** Returns the retry strategy for this step, or the default strategy if not specified. */
    public RetryStrategy retryStrategy() {
        return retryStrategy != null ? retryStrategy : RetryStrategies.Presets.DEFAULT;
    }

    /** Returns the delivery semantics per retry for this step. */
    public StepSemantics semanticsPerRetry() {
        return semanticsPerRetry != null ? semanticsPerRetry : StepSemantics.AT_LEAST_ONCE_PER_RETRY;
    }

    /** Returns the custom serializer for this step, or null if not specified (uses default SerDes). */
    public SerDes serDes() {
        return serDes;
    }

    public Builder toBuilder() {
        return new Builder(retryStrategy, semanticsPerRetry, serDes);
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
        private StepSemantics semanticsPerRetry;
        private SerDes serDes;

        public Builder(RetryStrategy retryStrategy, StepSemantics semanticsPerRetry, SerDes serDes) {
            this.retryStrategy = retryStrategy;
            this.semanticsPerRetry = semanticsPerRetry;
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
         * Sets the delivery semantics per retry for the step.
         *
         * @param semanticsPerRetry the delivery semantics to use
         * @return this builder for method chaining
         */
        public Builder semanticsPerRetry(StepSemantics semanticsPerRetry) {
            this.semanticsPerRetry = semanticsPerRetry;
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
