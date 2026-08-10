// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context.extension;

import java.util.Objects;
import java.util.function.BiFunction;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Canonical implementation of the built-in wait-for-condition extension. */
public final class WaitForConditionExtension {
    private WaitForConditionExtension() {}

    public static <T> DurableFuture<T> execute(
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
}
