// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.time.Duration;
import java.util.function.Supplier;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.ExtensionStepConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;

/**
 * An opaque, one-shot reservation for a primitive operation.
 *
 * <p>The SDK allocates the operation ID when the reservation is created. Reserving operations in deterministic order
 * allows an extension to launch them later in a different order without changing their IDs.
 */
public interface ExtensionOperation {
    default <T> T step(Class<T> resultType, Supplier<T> function) {
        return step(TypeToken.get(resultType), function);
    }

    default <T> T step(TypeToken<T> resultType, Supplier<T> function) {
        return step(resultType, function, StepConfig.builder().build());
    }

    default <T> T step(Class<T> resultType, Supplier<T> function, StepConfig config) {
        return step(TypeToken.get(resultType), function, config);
    }

    default <T> T step(TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        return stepAsync(resultType, function, config).get();
    }

    default <T> DurableFuture<T> stepAsync(Class<T> resultType, Supplier<T> function) {
        return stepAsync(TypeToken.get(resultType), function);
    }

    default <T> DurableFuture<T> stepAsync(TypeToken<T> resultType, Supplier<T> function) {
        return stepAsync(resultType, function, StepConfig.builder().build());
    }

    default <T> DurableFuture<T> stepAsync(Class<T> resultType, Supplier<T> function, StepConfig config) {
        return stepAsync(TypeToken.get(resultType), function, config);
    }

    <T> DurableFuture<T> stepAsync(TypeToken<T> resultType, Supplier<T> function, StepConfig config);

    default <T> T step(String subType, Class<T> resultType, Supplier<T> function) {
        return step(subType, TypeToken.get(resultType), function);
    }

    default <T> T step(String subType, TypeToken<T> resultType, Supplier<T> function) {
        return step(subType, resultType, function, StepConfig.builder().build());
    }

    default <T> T step(String subType, Class<T> resultType, Supplier<T> function, StepConfig config) {
        return step(subType, TypeToken.get(resultType), function, config);
    }

    default <T> T step(String subType, TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        return stepAsync(subType, resultType, function, config).get();
    }

    default <T> DurableFuture<T> stepAsync(String subType, Class<T> resultType, Supplier<T> function) {
        return stepAsync(subType, TypeToken.get(resultType), function);
    }

    default <T> DurableFuture<T> stepAsync(String subType, TypeToken<T> resultType, Supplier<T> function) {
        return stepAsync(subType, resultType, function, StepConfig.builder().build());
    }

    default <T> DurableFuture<T> stepAsync(
            String subType, Class<T> resultType, Supplier<T> function, StepConfig config) {
        return stepAsync(subType, TypeToken.get(resultType), function, config);
    }

    <T> DurableFuture<T> stepAsync(String subType, TypeToken<T> resultType, Supplier<T> function, StepConfig config);

    default <T> T step(
            String subType, Class<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config) {
        return step(subType, TypeToken.get(resultType), function, config);
    }

    default <T> T step(
            String subType, TypeToken<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config) {
        return stepAsync(subType, resultType, function, config).get();
    }

    default <T> DurableFuture<T> stepAsync(
            String subType, Class<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config) {
        return stepAsync(subType, TypeToken.get(resultType), function, config);
    }

    <T> DurableFuture<T> stepAsync(
            String subType, TypeToken<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config);

    default Void wait(Duration duration) {
        return waitAsync(duration).get();
    }

    DurableFuture<Void> waitAsync(Duration duration);

    default Void wait(String subType, Duration duration) {
        return waitAsync(subType, duration).get();
    }

    DurableFuture<Void> waitAsync(String subType, Duration duration);

    default <T, U> T invoke(String functionName, U payload, Class<T> resultType) {
        return invoke(functionName, payload, TypeToken.get(resultType));
    }

    default <T, U> T invoke(String functionName, U payload, TypeToken<T> resultType) {
        return invoke(functionName, payload, resultType, InvokeConfig.builder().build());
    }

    default <T, U> T invoke(String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invoke(functionName, payload, TypeToken.get(resultType), config);
    }

    default <T, U> T invoke(String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        return invokeAsync(functionName, payload, resultType, config).get();
    }

    default <T, U> DurableFuture<T> invokeAsync(String functionName, U payload, Class<T> resultType) {
        return invokeAsync(functionName, payload, TypeToken.get(resultType));
    }

    default <T, U> DurableFuture<T> invokeAsync(String functionName, U payload, TypeToken<T> resultType) {
        return invokeAsync(
                functionName, payload, resultType, InvokeConfig.builder().build());
    }

    default <T, U> DurableFuture<T> invokeAsync(
            String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(functionName, payload, TypeToken.get(resultType), config);
    }

    <T, U> DurableFuture<T> invokeAsync(String functionName, U payload, TypeToken<T> resultType, InvokeConfig config);

    default <T, U> T invoke(String subType, String functionName, U payload, Class<T> resultType) {
        return invoke(subType, functionName, payload, TypeToken.get(resultType));
    }

    default <T, U> T invoke(String subType, String functionName, U payload, TypeToken<T> resultType) {
        return invoke(
                subType,
                functionName,
                payload,
                resultType,
                InvokeConfig.builder().build());
    }

    default <T, U> T invoke(String subType, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invoke(subType, functionName, payload, TypeToken.get(resultType), config);
    }

    default <T, U> T invoke(
            String subType, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        return invokeAsync(subType, functionName, payload, resultType, config).get();
    }

    default <T, U> DurableFuture<T> invokeAsync(String subType, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(subType, functionName, payload, TypeToken.get(resultType));
    }

    default <T, U> DurableFuture<T> invokeAsync(
            String subType, String functionName, U payload, TypeToken<T> resultType) {
        return invokeAsync(
                subType,
                functionName,
                payload,
                resultType,
                InvokeConfig.builder().build());
    }

    default <T, U> DurableFuture<T> invokeAsync(
            String subType, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(subType, functionName, payload, TypeToken.get(resultType), config);
    }

    <T, U> DurableFuture<T> invokeAsync(
            String subType, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config);

    default <T> DurableCallbackFuture<T> createCallback(Class<T> resultType) {
        return createCallback(TypeToken.get(resultType));
    }

    default <T> DurableCallbackFuture<T> createCallback(TypeToken<T> resultType) {
        return createCallback(resultType, CallbackConfig.builder().build());
    }

    default <T> DurableCallbackFuture<T> createCallback(Class<T> resultType, CallbackConfig config) {
        return createCallback(TypeToken.get(resultType), config);
    }

    <T> DurableCallbackFuture<T> createCallback(TypeToken<T> resultType, CallbackConfig config);

    default <T> DurableCallbackFuture<T> createCallback(String subType, Class<T> resultType) {
        return createCallback(subType, TypeToken.get(resultType));
    }

    default <T> DurableCallbackFuture<T> createCallback(String subType, TypeToken<T> resultType) {
        return createCallback(subType, resultType, CallbackConfig.builder().build());
    }

    default <T> DurableCallbackFuture<T> createCallback(String subType, Class<T> resultType, CallbackConfig config) {
        return createCallback(subType, TypeToken.get(resultType), config);
    }

    <T> DurableCallbackFuture<T> createCallback(String subType, TypeToken<T> resultType, CallbackConfig config);

    default <T> T runInChildContext(Class<T> resultType, Supplier<T> function) {
        return runInChildContext(TypeToken.get(resultType), function);
    }

    default <T> T runInChildContext(TypeToken<T> resultType, Supplier<T> function) {
        return runInChildContext(
                resultType, function, RunInChildContextConfig.builder().build());
    }

    default <T> T runInChildContext(Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContext(TypeToken.get(resultType), function, config);
    }

    default <T> T runInChildContext(TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContextAsync(resultType, function, config).get();
    }

    default <T> DurableFuture<T> runInChildContextAsync(Class<T> resultType, Supplier<T> function) {
        return runInChildContextAsync(TypeToken.get(resultType), function);
    }

    default <T> DurableFuture<T> runInChildContextAsync(TypeToken<T> resultType, Supplier<T> function) {
        return runInChildContextAsync(
                resultType, function, RunInChildContextConfig.builder().build());
    }

    default <T> DurableFuture<T> runInChildContextAsync(
            Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContextAsync(TypeToken.get(resultType), function, config);
    }

    <T> DurableFuture<T> runInChildContextAsync(
            TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config);

    default <T> T runInChildContext(String subType, Class<T> resultType, Supplier<T> function) {
        return runInChildContext(subType, TypeToken.get(resultType), function);
    }

    default <T> T runInChildContext(String subType, TypeToken<T> resultType, Supplier<T> function) {
        return runInChildContext(
                subType, resultType, function, RunInChildContextConfig.builder().build());
    }

    default <T> T runInChildContext(
            String subType, Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContext(subType, TypeToken.get(resultType), function, config);
    }

    default <T> T runInChildContext(
            String subType, TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContextAsync(subType, resultType, function, config).get();
    }

    default <T> DurableFuture<T> runInChildContextAsync(String subType, Class<T> resultType, Supplier<T> function) {
        return runInChildContextAsync(subType, TypeToken.get(resultType), function);
    }

    default <T> DurableFuture<T> runInChildContextAsync(String subType, TypeToken<T> resultType, Supplier<T> function) {
        return runInChildContextAsync(
                subType, resultType, function, RunInChildContextConfig.builder().build());
    }

    default <T> DurableFuture<T> runInChildContextAsync(
            String subType, Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return runInChildContextAsync(subType, TypeToken.get(resultType), function, config);
    }

    <T> DurableFuture<T> runInChildContextAsync(
            String subType, TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config);
}
