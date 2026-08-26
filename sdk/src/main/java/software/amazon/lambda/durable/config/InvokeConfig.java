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
    private final boolean usePersistedSerDesForPayload;

    public InvokeConfig(Builder builder) {
        this.payloadSerDes = builder.payloadSerDes;
        this.resultSerDes = builder.resultSerDes;
        this.tenantId = builder.tenantId;
        this.usePersistedSerDesForPayload = builder.usePersistedSerDesForPayload;
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

    /** Returns whether the target should decode this invoke payload with its persisted SerDes pipeline. */
    public boolean usePersistedSerDesForPayload() {
        return usePersistedSerDesForPayload;
    }

    public static Builder builder() {
        return new Builder(null, null, null, false);
    }

    public Builder toBuilder() {
        return new Builder(payloadSerDes, resultSerDes, tenantId, usePersistedSerDesForPayload);
    }

    /** Builder for creating InvokeConfig instances. */
    public static class Builder {
        private SerDes payloadSerDes;
        private SerDes resultSerDes;
        private String tenantId;
        private boolean usePersistedSerDesForPayload;

        private Builder(
                SerDes payloadSerDes, SerDes resultSerDes, String tenantId, boolean usePersistedSerDesForPayload) {
            this.payloadSerDes = payloadSerDes;
            this.resultSerDes = resultSerDes;
            this.tenantId = tenantId;
            this.usePersistedSerDesForPayload = usePersistedSerDesForPayload;
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
         * <p>If not specified, the invoke operation uses the handler's context-free input codec by default, or its
         * persisted SerDes when {@link #usePersistedSerDesForPayload(boolean)} is enabled. This method allows
         * per-invoke customization of serialization behavior, useful for invoke operations that need special handling.
         *
         * <p>By default, the serialized value is sent unchanged and must match the target function's ordinary input
         * wire format. When {@link #usePersistedSerDesForPayload(boolean)} is enabled, it must instead be compatible
         * with the target durable handler's persisted SerDes.
         *
         * @param payloadSerDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder payloadSerDes(SerDes payloadSerDes) {
            this.payloadSerDes = payloadSerDes;
            return this;
        }

        /**
         * Selects whether a compatible durable target should deserialize the invoke payload with its persisted SerDes
         * pipeline.
         *
         * <p>This is disabled by default so standard Lambda functions, non-Java durable functions, and older Java SDK
         * versions continue to receive the configured serialized payload unchanged. Enable it only when the target is a
         * Java durable handler that supports the SDK's chained-invoke payload frame, configures a compatible persisted
         * SerDes pipeline, and enables
         * {@link software.amazon.lambda.durable.DurableConfig.Builder#withPersistedSerDesForChainedInvokePayloads(boolean)}.
         *
         * <p>When enabled without an explicit {@link #payloadSerDes(SerDes)}, the caller's persisted SerDes is used.
         * Otherwise, the caller's context-free input codec is the default payload serializer.
         *
         * @param enabled whether the compatible target should use its persisted SerDes pipeline
         * @return this builder for method chaining
         */
        public Builder usePersistedSerDesForPayload(boolean enabled) {
            this.usePersistedSerDesForPayload = enabled;
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
