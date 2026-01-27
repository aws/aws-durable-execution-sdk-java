// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;

/** Configuration for callback operations. */
public class CallbackConfig {
    private final Duration timeout;
    private final Duration heartbeatTimeout;
    private final SerDes serDes;

    private CallbackConfig(Builder builder) {
        this.timeout = builder.timeout;
        this.heartbeatTimeout = builder.heartbeatTimeout;
        this.serDes = builder.serDes;
    }

    public Duration timeout() {
        return timeout;
    }

    public Duration heartbeatTimeout() {
        return heartbeatTimeout;
    }

    /** @return the custom serializer for this callback, or null if not specified (uses default SerDes) */
    public SerDes serDes() {
        return serDes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Duration timeout;
        private Duration heartbeatTimeout;
        private SerDes serDes;

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder heartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
            return this;
        }

        /**
         * Sets a custom serializer for the callback.
         *
         * <p>If not specified, the callback will use the default SerDes configured for the handler. This allows
         * per-callback customization of serialization behavior, useful for callbacks that need special handling (e.g.,
         * custom date formats, encryption, compression).
         *
         * @param serDes the custom serializer to use, or null to use the default
         * @return this builder for method chaining
         */
        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        public CallbackConfig build() {
            return new CallbackConfig(this);
        }
    }
}
