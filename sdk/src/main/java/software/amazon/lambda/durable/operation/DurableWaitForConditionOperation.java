// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.WaitForConditionWaitStrategy;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable wait-for-condition operations. */
public final class DurableWaitForConditionOperation {
    private DurableWaitForConditionOperation() {}

    public static <T> T waitForCondition(
            String name, Class<T> resultType, Function<T, WaitForConditionResult<T>> checkFunction) {
        return waitForConditionAsync(name, resultType, checkFunction).get();
    }

    public static <T> T waitForCondition(
            String name, TypeToken<T> resultType, Function<T, WaitForConditionResult<T>> checkFunction) {
        return waitForConditionAsync(name, resultType, checkFunction).get();
    }

    public static <T> T waitForCondition(
            String name,
            Class<T> resultType,
            Function<T, WaitForConditionResult<T>> checkFunction,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, resultType, checkFunction, config).get();
    }

    public static <T> T waitForCondition(
            String name,
            TypeToken<T> resultType,
            Function<T, WaitForConditionResult<T>> checkFunction,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, resultType, checkFunction, config).get();
    }

    public static <T> DurableFuture<T> waitForConditionAsync(
            String name, Class<T> resultType, Function<T, WaitForConditionResult<T>> checkFunction) {
        return waitForConditionAsync(name, TypeToken.get(resultType), checkFunction);
    }

    public static <T> DurableFuture<T> waitForConditionAsync(
            String name, TypeToken<T> resultType, Function<T, WaitForConditionResult<T>> checkFunction) {
        return waitForConditionAsync(
                name,
                resultType,
                checkFunction,
                WaitForConditionConfig.<T>builder().build());
    }

    public static <T> DurableFuture<T> waitForConditionAsync(
            String name,
            Class<T> resultType,
            Function<T, WaitForConditionResult<T>> checkFunction,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, TypeToken.get(resultType), checkFunction, config);
    }

    public static <T> DurableFuture<T> waitForConditionAsync(
            String name,
            TypeToken<T> resultType,
            Function<T, WaitForConditionResult<T>> checkFunction,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(
                ExtensionContext.getCurrentContext(), name, resultType, adapt(checkFunction), config);
    }

    public static <T> DurableFuture<T> waitForConditionAsync(
            ExtensionContext context,
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunction,
            WaitForConditionConfig<T> config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(checkFunction, "checkFunction cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);

        var future = context.reserve(name)
                .stepAsync(
                        OperationSubType.WAIT_FOR_CONDITION.getValue(),
                        resultType,
                        state -> evaluate(state, checkFunction, config),
                        ExtensionStepConfig.<T>builder()
                                .initialState(config.initialState())
                                .serDes(config.serDes())
                                .build());
        return new WaitForConditionFuture<>(future);
    }

    private static <T> BiFunction<T, StepContext, WaitForConditionResult<T>> adapt(
            Function<T, WaitForConditionResult<T>> checkFunction) {
        Objects.requireNonNull(checkFunction, "checkFunction cannot be null");
        return (state, ignored) -> checkFunction.apply(state);
    }

    private static <T> ExtensionStepResult<T> evaluate(
            T state,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunction,
            WaitForConditionConfig<T> config) {
        var stepContext = StepContext.getCurrentContext();
        var result = Objects.requireNonNull(
                checkFunction.apply(state, stepContext), "waitForCondition check result cannot be null");
        if (result.isDone()) {
            return ExtensionStepResult.succeed(result.value());
        }
        var delay = config.waitStrategy().evaluate(result.value(), stepContext.getAttempt());
        return ExtensionStepResult.retry(result.value(), delay);
    }

    /** Configuration for durable wait-for-condition operations. */
    public static final class WaitForConditionConfig<T> {
        private final WaitForConditionWaitStrategy<T> waitStrategy;
        private final SerDes serDes;
        private final T initialState;

        private WaitForConditionConfig(Builder<T> builder) {
            waitStrategy = Objects.requireNonNullElseGet(builder.waitStrategy, WaitStrategies::defaultStrategy);
            serDes = builder.serDes;
            initialState = builder.initialState;
        }

        public WaitForConditionWaitStrategy<T> waitStrategy() {
            return waitStrategy;
        }

        public SerDes serDes() {
            return serDes;
        }

        public T initialState() {
            return initialState;
        }

        public static <T> Builder<T> builder() {
            return new Builder<>();
        }

        public Builder<T> toBuilder() {
            return new Builder<T>().waitStrategy(waitStrategy).serDes(serDes).initialState(initialState);
        }

        /** Builder for {@link WaitForConditionConfig}. */
        public static final class Builder<T> {
            private WaitForConditionWaitStrategy<T> waitStrategy;
            private SerDes serDes;
            private T initialState;

            private Builder() {}

            public Builder<T> waitStrategy(WaitForConditionWaitStrategy<T> waitStrategy) {
                this.waitStrategy = waitStrategy;
                return this;
            }

            public Builder<T> serDes(SerDes serDes) {
                this.serDes = serDes;
                return this;
            }

            public Builder<T> initialState(T initialState) {
                this.initialState = initialState;
                return this;
            }

            public WaitForConditionConfig<T> build() {
                return new WaitForConditionConfig<>(this);
            }
        }
    }
}
