// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import software.amazon.lambda.durable.serde.SerDes;

/** Configuration for an extension CHAINED_INVOKE primitive. */
public final class ExtensionInvokeConfig {
    private final SerDes payloadSerDes;
    private final SerDes resultSerDes;
    private final String tenantId;

    private ExtensionInvokeConfig(Builder builder) {
        payloadSerDes = builder.payloadSerDes;
        resultSerDes = builder.resultSerDes;
        tenantId = builder.tenantId;
    }

    public SerDes payloadSerDes() {
        return payloadSerDes;
    }

    public SerDes serDes() {
        return resultSerDes;
    }

    public String tenantId() {
        return tenantId;
    }

    public Builder toBuilder() {
        return new Builder().payloadSerDes(payloadSerDes).serDes(resultSerDes).tenantId(tenantId);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ExtensionInvokeConfig}. */
    public static final class Builder {
        private SerDes payloadSerDes;
        private SerDes resultSerDes;
        private String tenantId;

        private Builder() {}

        public Builder payloadSerDes(SerDes payloadSerDes) {
            this.payloadSerDes = payloadSerDes;
            return this;
        }

        public Builder serDes(SerDes resultSerDes) {
            this.resultSerDes = resultSerDes;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public ExtensionInvokeConfig build() {
            return new ExtensionInvokeConfig(this);
        }
    }
}
