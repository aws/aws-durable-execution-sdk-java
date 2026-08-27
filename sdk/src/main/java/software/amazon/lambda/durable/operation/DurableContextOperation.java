// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.RUN_IN_CHILD_CONTEXT;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable CONTEXT operations. */
public final class DurableContextOperation {
    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    private DurableContextOperation() {}

    public static <T> T runInChildContext(String name, Class<T> resultType, Supplier<T> function) {
        return runInChildContext(name, TypeToken.get(resultType), function);
    }

    public static <T> T runInChildContext(String name, TypeToken<T> resultType, Supplier<T> function) {
        return runInChildContext(
                name, resultType, function, RunInChildContextConfig.builder().build());
    }

    public static <T> T runInChildContext(
            String name, Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContext(name, TypeToken.get(resultType), function, config);
    }

    public static <T> T runInChildContext(
            String name, TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContextAsync(name, resultType, function, config).get();
    }

    public static <T> DurableFuture<T> runInChildContextAsync(String name, Class<T> resultType, Supplier<T> function) {
        return runInChildContextAsync(name, TypeToken.get(resultType), function);
    }

    public static <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> resultType, Supplier<T> function) {
        return runInChildContextAsync(
                name, resultType, function, RunInChildContextConfig.builder().build());
    }

    public static <T> DurableFuture<T> runInChildContextAsync(
            String name, Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContextAsync(name, TypeToken.get(resultType), function, config);
    }

    public static <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        Objects.requireNonNull(function, "function cannot be null");
        return runInChildContextAsync(
                ExtensionContext.getCurrentContext(), name, resultType, ignored -> function.get(), config);
    }

    public static <T> DurableFuture<T> runInChildContextAsync(
            ExtensionContext context,
            String name,
            TypeToken<T> resultType,
            Function<DurableContext, T> function,
            RunInChildContextConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(config, "RunInChildContextConfig cannot be null");
        ParameterValidator.validateOperationName(name);

        return CompletionStageDurableFuture.from(
                context,
                context.reserve(name)
                        .runInChildContextAsync(
                                RUN_IN_CHILD_CONTEXT.getValue(),
                                resultType,
                                () -> CompletableFuture.completedFuture(ExtensionContextResult.replayChildrenAboveSize(
                                        function.apply(DurableContext.requireCurrentContext()),
                                        null,
                                        LARGE_RESULT_THRESHOLD)),
                                ExtensionContextConfig.builder()
                                        .serDes(config.serDes())
                                        .isVirtual(config.isVirtual())
                                        .build()));
    }

    /** Configuration for durable CONTEXT operations. */
    public static final class RunInChildContextConfig {
        private final SerDes serDes;
        private final boolean virtual;

        private RunInChildContextConfig(Builder builder) {
            serDes = builder.serDes;
            virtual = Objects.requireNonNullElse(builder.virtual, false);
        }

        public SerDes serDes() {
            return serDes;
        }

        public Boolean isVirtual() {
            return virtual;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder().serDes(serDes).isVirtual(virtual);
        }

        /** Builder for {@link RunInChildContextConfig}. */
        public static final class Builder {
            private SerDes serDes;
            private Boolean virtual;

            private Builder() {}

            public Builder serDes(SerDes serDes) {
                this.serDes = serDes;
                return this;
            }

            public Builder isVirtual(Boolean virtual) {
                this.virtual = virtual;
                return this;
            }

            public RunInChildContextConfig build() {
                return new RunInChildContextConfig(this);
            }
        }
    }
}
