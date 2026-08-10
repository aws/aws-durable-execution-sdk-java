// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.time.Duration;
import java.util.function.Supplier;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;

/**
 * Context-free static facades for SDK-owned primitive durable operations.
 *
 * <p>The equivalent instance methods on {@link DurableContext} remain supported for backward compatibility.
 */
public final class DurableCoreOperations {
    private DurableCoreOperations() {}

    public static <T> T step(String name, Class<T> resultType, Supplier<T> function) {
        return currentContext().step(name, resultType, ignored -> function.get());
    }

    public static <T> T step(String name, TypeToken<T> resultType, Supplier<T> function) {
        return currentContext().step(name, resultType, ignored -> function.get());
    }

    public static <T> T step(String name, Class<T> resultType, Supplier<T> function, StepConfig config) {
        return currentContext().step(name, resultType, ignored -> function.get(), config);
    }

    public static <T> T step(String name, TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        return currentContext().step(name, resultType, ignored -> function.get(), config);
    }

    public static <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> function) {
        return currentContext().stepAsync(name, resultType, ignored -> function.get());
    }

    public static <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Supplier<T> function) {
        return currentContext().stepAsync(name, resultType, ignored -> function.get());
    }

    public static <T> DurableFuture<T> stepAsync(
            String name, Class<T> resultType, Supplier<T> function, StepConfig config) {
        return currentContext().stepAsync(name, resultType, ignored -> function.get(), config);
    }

    public static <T> DurableFuture<T> stepAsync(
            String name, TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        return currentContext().stepAsync(name, resultType, ignored -> function.get(), config);
    }

    public static Void wait(String name, Duration duration) {
        return currentContext().wait(name, duration);
    }

    public static DurableFuture<Void> waitAsync(String name, Duration duration) {
        return currentContext().waitAsync(name, duration);
    }

    public static <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType) {
        return currentContext().invoke(name, functionName, payload, resultType);
    }

    public static <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> resultType) {
        return currentContext().invoke(name, functionName, payload, resultType);
    }

    public static <T, U> T invoke(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return currentContext().invoke(name, functionName, payload, resultType, config);
    }

    public static <T, U> T invoke(
            String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        return currentContext().invoke(name, functionName, payload, resultType, config);
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType) {
        return currentContext().invokeAsync(name, functionName, payload, resultType);
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, TypeToken<T> resultType) {
        return currentContext().invokeAsync(name, functionName, payload, resultType);
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return currentContext().invokeAsync(name, functionName, payload, resultType, config);
    }

    public static <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        return currentContext().invokeAsync(name, functionName, payload, resultType, config);
    }

    public static <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType) {
        return currentContext().createCallback(name, resultType);
    }

    public static <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> resultType) {
        return currentContext().createCallback(name, resultType);
    }

    public static <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType, CallbackConfig config) {
        return currentContext().createCallback(name, resultType, config);
    }

    public static <T> DurableCallbackFuture<T> createCallback(
            String name, TypeToken<T> resultType, CallbackConfig config) {
        return currentContext().createCallback(name, resultType, config);
    }

    public static <T> T runInChildContext(String name, Class<T> resultType, Supplier<T> function) {
        return currentContext().runInChildContext(name, resultType, ignored -> function.get());
    }

    public static <T> T runInChildContext(String name, TypeToken<T> resultType, Supplier<T> function) {
        return currentContext().runInChildContext(name, resultType, ignored -> function.get());
    }

    public static <T> T runInChildContext(
            String name, Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return currentContext().runInChildContext(name, resultType, ignored -> function.get(), config);
    }

    public static <T> T runInChildContext(
            String name, TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return currentContext().runInChildContext(name, resultType, ignored -> function.get(), config);
    }

    public static <T> DurableFuture<T> runInChildContextAsync(String name, Class<T> resultType, Supplier<T> function) {
        return currentContext().runInChildContextAsync(name, resultType, ignored -> function.get());
    }

    public static <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> resultType, Supplier<T> function) {
        return currentContext().runInChildContextAsync(name, resultType, ignored -> function.get());
    }

    public static <T> DurableFuture<T> runInChildContextAsync(
            String name, Class<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return currentContext().runInChildContextAsync(name, resultType, ignored -> function.get(), config);
    }

    public static <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        return currentContext().runInChildContextAsync(name, resultType, ignored -> function.get(), config);
    }

    private static DurableContext currentContext() {
        return DurableContext.getCurrentContext();
    }
}
