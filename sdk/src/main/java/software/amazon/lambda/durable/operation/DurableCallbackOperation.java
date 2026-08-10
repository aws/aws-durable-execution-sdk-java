// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.CALLBACK;

import java.time.Duration;
import java.util.Objects;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.extension.ExtensionCallbackConfig;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable CALLBACK operations. */
public final class DurableCallbackOperation {
    private DurableCallbackOperation() {}

    public static <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType) {
        return createCallback(name, TypeToken.get(resultType));
    }

    public static <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> resultType) {
        return createCallback(name, resultType, CallbackConfig.builder().build());
    }

    public static <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType, CallbackConfig config) {
        return createCallback(name, TypeToken.get(resultType), config);
    }

    public static <T> DurableCallbackFuture<T> createCallback(
            String name, TypeToken<T> resultType, CallbackConfig config) {
        return createCallback(ExtensionContext.getCurrentContext(), name, resultType, config);
    }

    public static <T> DurableCallbackFuture<T> createCallback(
            ExtensionContext context, String name, TypeToken<T> resultType, CallbackConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);
        return context.reserve(name).createCallback(CALLBACK.getValue(), resultType, extensionConfig(config));
    }

    static ExtensionCallbackConfig extensionConfig(CallbackConfig config) {
        return ExtensionCallbackConfig.builder()
                .timeout(config.timeout())
                .heartbeatTimeout(config.heartbeatTimeout())
                .serDes(config.serDes())
                .build();
    }

    /** Configuration for durable CALLBACK operations. */
    public static final class CallbackConfig {
        private final Duration timeout;
        private final Duration heartbeatTimeout;
        private final SerDes serDes;

        private CallbackConfig(Builder builder) {
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

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder()
                    .timeout(timeout)
                    .heartbeatTimeout(heartbeatTimeout)
                    .serDes(serDes);
        }

        /** Builder for {@link CallbackConfig}. */
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

            public CallbackConfig build() {
                return new CallbackConfig(this);
            }
        }
    }
}
