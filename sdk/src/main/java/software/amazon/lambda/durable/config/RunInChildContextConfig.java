// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration options for RunInChildContext operations in durable executions.
 *
 * <p>This class provides a builder pattern for configuring various aspects of RunInChildContext execution.
 */
public class RunInChildContextConfig {
    private final SerDes serDes;

    private RunInChildContextConfig(Builder builder) {
        this.serDes = builder.serDes;
    }

    /**
     * Returns the custom serializer for this RunInChildContext operation, or null if not specified (uses default
     * SerDes).
     */
    public SerDes serDes() {
        return serDes;
    }

    public Builder toBuilder() {
        return new Builder(serDes);
    }

    /**
     * Creates a new builder for RunInChildContextConfig.
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
         * <p>If not specified, the RunInChildContext operation will use the default SerDes configured for the handler.
         * This allows per-operation customization of serialization behavior, useful for operations that need special
         * handling (e.g., custom date formats, encryption, compression).
         *
         * @param serDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        /**
         * Builds the RunInChildContextConfig instance.
         *
         * @return a new StepConfig with the configured options
         */
        public RunInChildContextConfig build() {
            return new RunInChildContextConfig(this);
        }
    }
}
