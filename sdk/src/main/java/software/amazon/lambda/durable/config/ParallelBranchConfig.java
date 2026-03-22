// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration options for parallel branch in durable executions.
 *
 * <p>This class provides a builder pattern for configuring various aspects of parallel branch execution
 */
public class ParallelBranchConfig {
    private final SerDes serDes;

    private ParallelBranchConfig(Builder builder) {
        this.serDes = builder.serDes;
    }

    /** Returns the custom serializer for this step, or null if not specified (uses default SerDes). */
    public SerDes serDes() {
        return serDes;
    }

    public Builder toBuilder() {
        return new Builder(serDes);
    }

    /**
     * Creates a new builder for ParallelBranchConfig.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder(null);
    }

    /** Builder for creating StepConfig instances. */
    public static class Builder {
        private SerDes serDes;

        public Builder(SerDes serDes) {
            this.serDes = serDes;
        }

        /**
         * Sets a custom serializer for the step.
         *
         * <p>If not specified, the parallel branch will use the default SerDes configured for the handler. This allows
         * per-branch customization of serialization behavior, useful for branches that need special handling (e.g.,
         * custom date formats, encryption, compression).
         *
         * @param serDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        /**
         * Builds the ParallelBranchConfig instance.
         *
         * @return a new StepConfig with the configured options
         */
        public ParallelBranchConfig build() {
            return new ParallelBranchConfig(this);
        }
    }
}
