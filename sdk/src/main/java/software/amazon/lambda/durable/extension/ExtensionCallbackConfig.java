// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.time.Duration;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Configuration for an extension CALLBACK primitive. */
public final class ExtensionCallbackConfig {
    private final Duration timeout;
    private final Duration heartbeatTimeout;
    private final SerDes serDes;

    private ExtensionCallbackConfig(Builder builder) {
        timeout = builder.timeout;
        heartbeatTimeout = builder.heartbeatTimeout;
        serDes = builder.serDes;
    }

    public Duration timeout() {
        return timeout;
    }

    public Duration heartbeatTimeout() {
        return heartbeatTimeout;
    }

    public SerDes serDes() {
        return serDes;
    }

    public Builder toBuilder() {
        return new Builder().timeout(timeout).heartbeatTimeout(heartbeatTimeout).serDes(serDes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ExtensionCallbackConfig}. */
    public static final class Builder {
        private Duration timeout;
        private Duration heartbeatTimeout;
        private SerDes serDes;

        private Builder() {}

        public Builder timeout(Duration timeout) {
            ParameterValidator.validateOptionalDuration(timeout, "Callback timeout");
            this.timeout = timeout;
            return this;
        }

        public Builder heartbeatTimeout(Duration heartbeatTimeout) {
            ParameterValidator.validateOptionalDuration(heartbeatTimeout, "Heartbeat timeout");
            this.heartbeatTimeout = heartbeatTimeout;
            return this;
        }

        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        public ExtensionCallbackConfig build() {
            return new ExtensionCallbackConfig(this);
        }
    }
}
