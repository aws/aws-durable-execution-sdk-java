// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.extension.WaitForConditionExtension;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/** Context-free static facades for durable wait-for-condition operations. */
public final class DurableWaitForConditionOperations {
    private DurableWaitForConditionOperations() {}

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
        return WaitForConditionExtension.execute(currentContext(), name, resultType, adapt(checkFunction), config);
    }

    private static <T> BiFunction<T, StepContext, WaitForConditionResult<T>> adapt(
            Function<T, WaitForConditionResult<T>> checkFunction) {
        Objects.requireNonNull(checkFunction, "checkFunction cannot be null");
        return (state, ignored) -> checkFunction.apply(state);
    }

    private static ExtensionContext currentContext() {
        return ExtensionContext.getCurrentContext();
    }
}
