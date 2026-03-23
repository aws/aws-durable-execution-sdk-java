// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for chained invoke operations.
 *
 * <p>Controls serialization of the invoke payload and result, and optionally specifies a tenant ID.
 */
public class InvokeConfig {
    private final SerDes payloadSerDes;
    private final SerDes resultSerDes;
    private final String tenantId;

    public InvokeConfig(Builder builder) {
        this.payloadSerDes = builder.payloadSerDes;
        this.resultSerDes = builder.resultSerDes;
        this.tenantId = builder.tenantId;
    }

    public SerDes payloadSerDes() {
        return this.payloadSerDes;
    }

    public SerDes serDes() {
        return this.resultSerDes;
    }

    public String tenantId() {
        return tenantId;
    }

    public static Builder builder() {
        return new Builder(null, null, null);
    }

    public Builder toBuilder() {
        return new Builder(payloadSerDes, resultSerDes, tenantId);
    }

    /** Builder for creating InvokeConfig instances. */
    public static class Builder {
        private SerDes payloadSerDes;
        private SerDes resultSerDes;
        private String tenantId;

        private Builder(SerDes payloadSerDes, SerDes resultSerDes, String tenantId) {
            this.payloadSerDes = payloadSerDes;
            this.resultSerDes = resultSerDes;
            this.tenantId = tenantId;
        }

        /**
         * Sets the tenant ID for the invoke operation.
         *
         * <p>The tenant ID is used to isolate execution state for different tenants. It's required when invoking
         * multi-tenant functions.
         *
         * @param tenantId the tenant ID to use
         * @return this builder for method chaining
         */
        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /**
         * Sets a custom serializer for the invoke operation payload.
         *
         * <p>If not specified, the invoke operation will use the default SerDes configured for the handler. This allows
         * per-invoke customization of serialization behavior, useful for invoke operations that need special handling
         * (e.g., custom date formats, encryption, compression).
         *
         * @param payloadSerDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder payloadSerDes(SerDes payloadSerDes) {
            this.payloadSerDes = payloadSerDes;
            return this;
        }

        /**
         * Sets a custom serializer for the invoke result.
         *
         * <p>If not specified, the invoke will use the default SerDes configured for the handler. This allows
         * per-invoke customization of serialization behavior, useful for invoke operations that need special handling
         * (e.g., custom date formats, encryption, compression).
         *
         * @param resultSerDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder serDes(SerDes resultSerDes) {
            this.resultSerDes = resultSerDes;
            return this;
        }

        /**
         * Builds the InvokeConfig instance.
         *
         * @return a new InvokeConfig with the configured options
         */
        public InvokeConfig build() {
            return new InvokeConfig(this);
        }
    }
}
