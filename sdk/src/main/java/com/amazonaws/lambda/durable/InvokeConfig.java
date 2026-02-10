// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;

public class InvokeConfig {
    private final Duration timeout;
    private final SerDes payloadSerDes;
    private final SerDes resultSerDes;
    private final String tenantId;

    public InvokeConfig(Builder builder) {
        this.timeout = builder.timeout;
        this.payloadSerDes = builder.payloadSerDes;
        this.resultSerDes = builder.resultSerDes;
        this.tenantId = builder.tenantId;
    }

    public Duration timeout() {
        return this.timeout;
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
        return new Builder(null, null, null, null);
    }

    public Builder toBuilder() {
        return new Builder(timeout, payloadSerDes, resultSerDes, tenantId);
    }

    /** Builder for creating InvokeConfig instances. */
    public static class Builder {
        private Duration timeout;
        private SerDes payloadSerDes;
        private SerDes resultSerDes;
        private String tenantId;

        private Builder(Duration timeout, SerDes payloadSerDes, SerDes resultSerDes, String tenantId) {
            this.timeout = timeout;
            this.payloadSerDes = payloadSerDes;
            this.resultSerDes = resultSerDes;
            this.tenantId = tenantId;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
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
         * Sets a custom serializer for the step.
         *
         * <p>If not specified, the step will use the default SerDes configured for the handler. This allows per-step
         * customization of serialization behavior, useful for steps that need special handling (e.g., custom date
         * formats, encryption, compression).
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
