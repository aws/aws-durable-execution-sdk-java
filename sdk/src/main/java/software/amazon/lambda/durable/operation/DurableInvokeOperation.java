// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.CHAINED_INVOKE;

import java.util.Objects;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionInvokeConfig;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable CHAINED_INVOKE operations. */
public final class DurableInvokeOperation {
    private DurableInvokeOperation() {}

    public static <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType) {
        return invoke(name, functionName, payload, TypeToken.get(resultType));
    }

    public static <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> resultType) {
        return invoke(
                name, functionName, payload, resultType, InvokeConfig.builder().build());
    }

    public static <T, U> T invoke(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invoke(name, functionName, payload, TypeToken.get(resultType), config);
    }

    public static <T, U> T invoke(
            String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, resultType, config).get();
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType));
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, TypeToken<T> resultType) {
        return invokeAsync(
                name, functionName, payload, resultType, InvokeConfig.builder().build());
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config);
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        return invokeAsync(ExtensionContext.getCurrentContext(), name, functionName, payload, resultType, config);
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            ExtensionContext context,
            String name,
            String functionName,
            U payload,
            TypeToken<T> resultType,
            InvokeConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);
        return context.reserve(name)
                .invokeAsync(CHAINED_INVOKE.getValue(), functionName, payload, resultType, extensionConfig(config));
    }

    private static ExtensionInvokeConfig extensionConfig(InvokeConfig config) {
        return ExtensionInvokeConfig.builder()
                .payloadSerDes(config.payloadSerDes())
                .serDes(config.serDes())
                .tenantId(config.tenantId())
                .build();
    }

    /** Configuration for durable CHAINED_INVOKE operations. */
    public static final class InvokeConfig {
        private final SerDes payloadSerDes;
        private final SerDes serDes;
        private final String tenantId;

        private InvokeConfig(Builder builder) {
            payloadSerDes = builder.payloadSerDes;
            serDes = builder.serDes;
            tenantId = builder.tenantId;
        }

        public SerDes payloadSerDes() {
            return payloadSerDes;
        }

        public SerDes serDes() {
            return serDes;
        }

        public String tenantId() {
            return tenantId;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder().payloadSerDes(payloadSerDes).serDes(serDes).tenantId(tenantId);
        }

        /** Builder for {@link InvokeConfig}. */
        public static final class Builder {
            private SerDes payloadSerDes;
            private SerDes serDes;
            private String tenantId;

            private Builder() {}

            public Builder payloadSerDes(SerDes payloadSerDes) {
                this.payloadSerDes = payloadSerDes;
                return this;
            }

            public Builder serDes(SerDes serDes) {
                this.serDes = serDes;
                return this;
            }

            public Builder tenantId(String tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public InvokeConfig build() {
                return new InvokeConfig(this);
            }
        }
    }
}
