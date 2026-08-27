// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.STEP;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.RetryStrategy;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable STEP operations. */
public final class DurableStepOperation {
    private DurableStepOperation() {}

    public static <T> T step(String name, Class<T> resultType, Supplier<T> function) {
        return step(name, TypeToken.get(resultType), function);
    }

    public static <T> T step(String name, TypeToken<T> resultType, Supplier<T> function) {
        return step(name, resultType, function, StepConfig.builder().build());
    }

    public static <T> T step(String name, Class<T> resultType, Supplier<T> function, StepConfig config) {
        return step(name, TypeToken.get(resultType), function, config);
    }

    public static <T> T step(String name, TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        return stepAsync(name, resultType, function, config).get();
    }

    public static <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> function) {
        return stepAsync(name, TypeToken.get(resultType), function);
    }

    public static <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Supplier<T> function) {
        return stepAsync(name, resultType, function, StepConfig.builder().build());
    }

    public static <T> DurableFuture<T> stepAsync(
            String name, Class<T> resultType, Supplier<T> function, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), function, config);
    }

    public static <T> DurableFuture<T> stepAsync(
            String name, TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);

        var context = ExtensionContext.getCurrentContext();
        return CompletionStageDurableFuture.from(
                context,
                STEP.name(),
                name,
                context.reserve(name)
                        .stepAsync(
                                STEP.getValue(),
                                resultType,
                                ignored ->
                                        CompletableFuture.completedFuture(ExtensionStepResult.succeed(function.get())),
                                extensionConfig(config)));
    }

    static <T> ExtensionStepConfig<T> extensionConfig(StepConfig config) {
        return ExtensionStepConfig.<T>builder()
                .serDes(config.serDes())
                .retryStrategy(adapt(config.retryStrategy()))
                .semanticsPerRetry(adapt(config.semanticsPerRetry()))
                .build();
    }

    private static <T> ExtensionStepConfig.RetryStrategy<T> adapt(RetryStrategy retryStrategy) {
        return (error, state, attempt) -> {
            var decision = retryStrategy.makeRetryDecision(error, attempt);
            return decision.shouldRetry()
                    ? ExtensionStepResult.retry(state, decision.delay())
                    : ExtensionStepResult.doNotRetry();
        };
    }

    private static ExtensionStepConfig.StepSemantics adapt(StepSemantics semantics) {
        return switch (semantics) {
            case AT_LEAST_ONCE_PER_RETRY -> ExtensionStepConfig.StepSemantics.AT_LEAST_ONCE_PER_RETRY;
            case AT_MOST_ONCE_PER_RETRY -> ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY;
        };
    }

    /** Configuration for durable STEP operations. */
    public static final class StepConfig {
        private final RetryStrategy retryStrategy;
        private final StepSemantics semanticsPerRetry;
        private final SerDes serDes;

        private StepConfig(Builder builder) {
            retryStrategy = Objects.requireNonNullElse(builder.retryStrategy, RetryStrategies.Presets.DEFAULT);
            semanticsPerRetry =
                    Objects.requireNonNullElse(builder.semanticsPerRetry, StepSemantics.AT_LEAST_ONCE_PER_RETRY);
            serDes = builder.serDes;
        }

        public RetryStrategy retryStrategy() {
            return retryStrategy;
        }

        public StepSemantics semanticsPerRetry() {
            return semanticsPerRetry;
        }

        public SerDes serDes() {
            return serDes;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder()
                    .retryStrategy(retryStrategy)
                    .semanticsPerRetry(semanticsPerRetry)
                    .serDes(serDes);
        }

        /** Builder for {@link StepConfig}. */
        public static final class Builder {
            private RetryStrategy retryStrategy;
            private StepSemantics semanticsPerRetry;
            private SerDes serDes;

            private Builder() {}

            public Builder retryStrategy(RetryStrategy retryStrategy) {
                this.retryStrategy = retryStrategy;
                return this;
            }

            public Builder semanticsPerRetry(StepSemantics semanticsPerRetry) {
                this.semanticsPerRetry = semanticsPerRetry;
                return this;
            }

            public Builder serDes(SerDes serDes) {
                this.serDes = serDes;
                return this;
            }

            public StepConfig build() {
                return new StepConfig(this);
            }
        }
    }
}
