// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for chained invoke operations.
 *
 * <p>Controls serialization of the invoke payload and result, and optionally specifies a tenant ID.
 */
public class InvokeConfig {
    private final SerDes payloadSerDes;
    private final SerDes resultSerDes;
    private final PayloadOffloader payloadOffloader;
    private final String tenantId;
    private final boolean usePayloadOffloaderForPayload;

    public InvokeConfig(Builder builder) {
        this.payloadSerDes = builder.payloadSerDes;
        this.resultSerDes = builder.resultSerDes;
        this.payloadOffloader = builder.payloadOffloader;
        this.tenantId = builder.tenantId;
        this.usePayloadOffloaderForPayload = builder.usePayloadOffloaderForPayload;
    }

    public SerDes payloadSerDes() {
        return this.payloadSerDes;
    }

    public SerDes serDes() {
        return this.resultSerDes;
    }

    /** Returns the offloader used for the invoke result, or null to inherit the global offloader. */
    public PayloadOffloader payloadOffloader() {
        return payloadOffloader;
    }

    public String tenantId() {
        return tenantId;
    }

    /** Returns whether this invoke should use the framed durable-target payload protocol. */
    public boolean usePayloadOffloaderForPayload() {
        return usePayloadOffloaderForPayload;
    }

    public static Builder builder() {
        return new Builder(null, null, null, false);
    }

    public Builder toBuilder() {
        return new Builder(payloadSerDes, resultSerDes, tenantId, usePayloadOffloaderForPayload)
                .payloadOffloader(payloadOffloader);
    }

    /** Builder for creating InvokeConfig instances. */
    public static class Builder {
        private SerDes payloadSerDes;
        private SerDes resultSerDes;
        private PayloadOffloader payloadOffloader;
        private String tenantId;
        private boolean usePayloadOffloaderForPayload;

        private Builder(
                SerDes payloadSerDes, SerDes resultSerDes, String tenantId, boolean usePayloadOffloaderForPayload) {
            this.payloadSerDes = payloadSerDes;
            this.resultSerDes = resultSerDes;
            this.tenantId = tenantId;
            this.usePayloadOffloaderForPayload = usePayloadOffloaderForPayload;
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
         * Selects whether a compatible durable target should use the framed payload protocol for this request and its
         * result or error.
         *
         * <p>This enables payload offloading for the request and lets the caller distinguish SDK-owned result/error
         * envelopes from ordinary Lambda data. It is disabled by default so standard Lambda functions and older SDK
         * versions continue to exchange ordinary serialized values unchanged.
         */
        public Builder usePayloadOffloaderForPayload(boolean enabled) {
            usePayloadOffloaderForPayload = enabled;
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
         * Sets the offloader for the invoke result and for the request when
         * {@link #usePayloadOffloaderForPayload(boolean)} is enabled.
         */
        public Builder payloadOffloader(PayloadOffloader payloadOffloader) {
            this.payloadOffloader = payloadOffloader;
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
