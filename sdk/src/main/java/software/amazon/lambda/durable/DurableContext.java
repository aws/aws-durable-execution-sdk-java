// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.time.Duration;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.BaseContext;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.WaitForConditionResult;

public interface DurableContext extends BaseContext {
    /**
     * Executes a durable step with the given name and blocks until it completes.
     *
     * <p>On first execution, runs {@code func} and checkpoints the result. On replay, returns the cached result without
     * re-executing.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a {@link StepContext}
     * @return the step result
     */
    default <T> T step(String name, Class<T> resultType, Function<StepContext, T> func) {
        return step(name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    /**
     * Executes a durable step with the given name and configuration, blocking until it completes.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a {@link StepContext}
     * @param config the step configuration (retry strategy, semantics, custom SerDes)
     * @return the step result
     */
    default <T> T step(String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        return stepAsync(name, resultType, func, config).get();
    }

    /**
     * Executes a durable step using a {@link TypeToken} for generic result types, blocking until it completes.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @return the step result
     */
    default <T> T step(String name, TypeToken<T> resultType, Function<StepContext, T> func) {
        return step(name, resultType, func, StepConfig.builder().build());
    }

    /**
     * Executes a durable step using a {@link TypeToken} and configuration, blocking until it completes.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @param config the step configuration (retry strategy, semantics, custom SerDes)
     * @return the step result
     */
    default <T> T step(String name, TypeToken<T> resultType, Function<StepContext, T> func, StepConfig config) {
        return stepAsync(name, resultType, func, config).get();
    }

    /**
     * Asynchronously executes a durable step, returning a {@link DurableFuture} that can be composed or blocked on.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a {@link StepContext}
     * @return a future representing the step result
     */
    default <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Function<StepContext, T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    /**
     * Asynchronously executes a durable step using custom configuration.
     *
     * <p>This is the core stepAsync implementation. All other step/stepAsync overloads delegate here.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param func the function to execute, receiving a {@link StepContext}
     * @param config the step configuration (retry strategy, semantics, custom SerDes)
     * @return a future representing the step result
     */
    default <T> DurableFuture<T> stepAsync(
            String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    /**
     * Asynchronously executes a durable step using a {@link TypeToken} for generic result types.
     *
     * <p>This is the core stepAsync implementation. All other step/stepAsync overloads delegate here.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @return a future representing the step result
     */
    default <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Function<StepContext, T> func) {
        return stepAsync(name, resultType, func, StepConfig.builder().build());
    }

    /**
     * Asynchronously executes a durable step using a {@link TypeToken} and custom configuration.
     *
     * <p>This is the core stepAsync implementation. All other step/stepAsync overloads delegate here.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @param config the step configuration (retry strategy, semantics, custom SerDes)
     * @return a future representing the step result
     */
    <T> DurableFuture<T> stepAsync(
            String name, TypeToken<T> resultType, Function<StepContext, T> func, StepConfig config);

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(
                        name,
                        TypeToken.get(resultType),
                        func,
                        StepConfig.builder().build())
                .get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, TypeToken.get(resultType), func, config).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> T step(String name, TypeToken<T> resultType, Supplier<T> func) {
        return stepAsync(name, resultType, func, StepConfig.builder().build()).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> T step(String name, TypeToken<T> resultType, Supplier<T> func, StepConfig config) {
        return stepAsync(name, resultType, func, config).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Supplier<T> func) {
        return stepAsync(name, resultType, func, StepConfig.builder().build());
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    default <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Supplier<T> func, StepConfig config) {
        return stepAsync(name, resultType, stepContext -> func.get(), config);
    }

    /**
     * Suspends execution for the specified duration without consuming compute resources.
     *
     * <p>On first execution, checkpoints a wait operation and suspends the Lambda. On replay after the duration has
     * elapsed, returns immediately.
     *
     * @param name the unique operation name within this context
     * @param duration the duration to wait
     * @return always {@code null}
     */
    default Void wait(String name, Duration duration) {
        return waitAsync(name, duration).get();
    }

    /**
     * Asynchronously suspends execution for the specified duration.
     *
     * @param name the unique operation name within this context
     * @param duration the duration to wait
     * @return a future that completes when the wait duration has elapsed
     */
    DurableFuture<Void> waitAsync(String name, Duration duration);

    /**
     * Invokes another Lambda function by name and blocks until the result is available.
     *
     * <p>On first execution, checkpoints a chained invoke operation that triggers the target function. On replay,
     * returns the cached result without re-invoking.
     *
     * @param <T> the result type
     * @param <U> the payload type
     * @param name the unique operation name within this context
     * @param functionName the ARN or name of the Lambda function to invoke
     * @param payload the input payload to send to the target function
     * @param resultType the result class for deserialization
     * @return the invocation result
     */
    default <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        TypeToken.get(resultType),
                        InvokeConfig.builder().build())
                .get();
    }

    /** Invokes another Lambda function with custom configuration, blocking until the result is available. */
    default <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config)
                .get();
    }

    /** Invokes another Lambda function using a {@link TypeToken} for generic result types, blocking until complete. */
    default <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> resultType) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        resultType,
                        InvokeConfig.builder().build())
                .get();
    }

    /** Invokes another Lambda function using a {@link TypeToken} and custom configuration, blocking until complete. */
    default <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, resultType, config).get();
    }

    /** Invokes another Lambda function using a {@link TypeToken} and custom configuration, blocking until complete. */
    default <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config);
    }

    /** Asynchronously invokes another Lambda function, returning a {@link DurableFuture}. */
    default <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                name,
                functionName,
                payload,
                TypeToken.get(resultType),
                InvokeConfig.builder().build());
    }

    /** Asynchronously invokes another Lambda function using a {@link TypeToken} for generic result types. */
    default <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, TypeToken<T> resultType) {
        return invokeAsync(
                name, functionName, payload, resultType, InvokeConfig.builder().build());
    }

    /**
     * Asynchronously invokes another Lambda function using a {@link TypeToken} and custom configuration.
     *
     * <p>This is the core invokeAsync implementation. All other invoke/invokeAsync overloads delegate here.
     *
     * @param <T> the result type
     * @param <U> the payload type
     * @param name the unique operation name within this context
     * @param functionName the ARN or name of the Lambda function to invoke
     * @param payload the input payload to send to the target function
     * @param resultType the type token for deserialization of generic result types
     * @param config the invoke configuration (custom SerDes for result and payload)
     * @return a future representing the invocation result
     */
    <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config);

    /** Creates a callback with custom configuration. */
    default <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType, CallbackConfig config) {
        return createCallback(name, TypeToken.get(resultType), config);
    }

    /** Creates a callback using a {@link TypeToken} for generic result types. */
    default <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> resultType) {
        return createCallback(name, resultType, CallbackConfig.builder().build());
    }

    /** Creates a callback with default configuration. */
    default <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType) {
        return createCallback(
                name, TypeToken.get(resultType), CallbackConfig.builder().build());
    }

    /**
     * Creates a callback operation that suspends execution until an external system completes it.
     *
     * <p>This is the core createCallback implementation. Returns a {@link DurableCallbackFuture} containing a callback
     * ID that external systems use to report completion via the Lambda Durable API.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic result types
     * @param config the callback configuration (custom SerDes)
     * @return a future containing the callback ID and eventual result
     */
    <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> resultType, CallbackConfig config);

    /**
     * Runs a function in a child context, blocking until it completes.
     *
     * <p>Child contexts provide isolated operation ID namespaces, allowing nested workflows to be composed without ID
     * collisions. On replay, the child context's operations are replayed independently.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a child {@link DurableContext}
     * @return the child context result
     */
    default <T> T runInChildContext(String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(
                        name,
                        TypeToken.get(resultType),
                        func,
                        RunInChildContextConfig.builder().build())
                .get();
    }

    /**
     * Runs a function in a child context using a {@link TypeToken} for generic result types, blocking until complete.
     */
    default <T> T runInChildContext(String name, TypeToken<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(
                        name,
                        resultType,
                        func,
                        RunInChildContextConfig.builder().build())
                .get();
    }

    /**
     * Runs a function in a child context, blocking until it completes.
     *
     * <p>Child contexts provide isolated operation ID namespaces, allowing nested workflows to be composed without ID
     * collisions. On replay, the child context's operations are replayed independently.
     *
     * @param name the operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a child {@link DurableContext}
     * @return the DurableFuture of the child context result
     */
    default <T> DurableFuture<T> runInChildContextAsync(
            String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(
                name,
                TypeToken.get(resultType),
                func,
                RunInChildContextConfig.builder().build());
    }

    /**
     * Runs a function in a child context, blocking until it completes.
     *
     * <p>Child contexts provide isolated operation ID namespaces, allowing nested workflows to be composed without ID
     * collisions. On replay, the child context's operations are replayed independently.
     *
     * @param name the operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a child {@link DurableContext}
     * @return the DurableFuture of the child context result
     */
    default <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(
                name, resultType, func, RunInChildContextConfig.builder().build());
    }

    /**
     * Runs a function in a child context, blocking until it completes.
     *
     * <p>Child contexts provide isolated operation ID namespaces, allowing nested workflows to be composed without ID
     * collisions. On replay, the child context's operations are replayed independently.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a child {@link DurableContext}
     * @return the child context result
     */
    default <T> T runInChildContext(
            String name, Class<T> resultType, Function<DurableContext, T> func, RunInChildContextConfig config) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func, config)
                .get();
    }

    /**
     * Runs a function in a child context, blocking until it completes.
     *
     * <p>Child contexts provide isolated operation ID namespaces, allowing nested workflows to be composed without ID
     * collisions. On replay, the child context's operations are replayed independently.
     *
     * @param name the operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a child {@link DurableContext}
     * @param config the configuration for the child context
     * @return the child context result
     */
    default <T> T runInChildContext(
            String name, TypeToken<T> resultType, Function<DurableContext, T> func, RunInChildContextConfig config) {
        return runInChildContextAsync(name, resultType, func, config).get();
    }

    /**
     * Runs a function in a child context, blocking until it completes.
     *
     * <p>Child contexts provide isolated operation ID namespaces, allowing nested workflows to be composed without ID
     * collisions. On replay, the child context's operations are replayed independently.
     *
     * @param name the operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a child {@link DurableContext}
     * @param config the configuration for the child context
     * @return the DurableFuture wrapping the child context result
     */
    default <T> DurableFuture<T> runInChildContextAsync(
            String name, Class<T> resultType, Function<DurableContext, T> func, RunInChildContextConfig config) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func, config);
    }

    /**
     * Runs a function in a child context, blocking until it completes.
     *
     * <p>Child contexts provide isolated operation ID namespaces, allowing nested workflows to be composed without ID
     * collisions. On replay, the child context's operations are replayed independently.
     *
     * @param name the operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a child {@link DurableContext}
     * @param config the configuration for the child context
     * @return the DurableFuture wrapping the child context result
     */
    <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> resultType, Function<DurableContext, T> func, RunInChildContextConfig config);

    default <I, O> MapResult<O> map(String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function) {
        return mapAsync(
                        name,
                        items,
                        TypeToken.get(resultType),
                        function,
                        MapConfig.builder().build())
                .get();
    }

    default <I, O> MapResult<O> map(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, TypeToken.get(resultType), function, config)
                .get();
    }

    default <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function) {
        return mapAsync(name, items, resultType, function, MapConfig.builder().build())
                .get();
    }

    default <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, resultType, function, config).get();
    }

    default <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function) {
        return mapAsync(
                name,
                items,
                TypeToken.get(resultType),
                function,
                MapConfig.builder().build());
    }

    default <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, TypeToken.get(resultType), function, config);
    }

    default <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function) {
        return mapAsync(name, items, resultType, function, MapConfig.builder().build());
    }

    <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config);

    /**
     * Creates a {@link ParallelDurableFuture} for executing multiple branches concurrently.
     *
     * @param config the parallel execution configuration
     * @return a new ParallelDurableFuture for registering and executing branches
     */
    ParallelDurableFuture parallel(String name, ParallelConfig config);

    /**
     * Executes a submitter function and waits for an external callback, blocking until the callback completes.
     *
     * <p>Combines a step (to run the submitter) and a callback (to receive the external result) in a child context. The
     * submitter receives a callback ID that external systems use to report completion.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param func the submitter function, receiving the callback ID and a {@link StepContext}
     * @return the callback result
     */
    default <T> T waitForCallback(String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name,
                        TypeToken.get(resultType),
                        func,
                        WaitForCallbackConfig.builder().build())
                .get();
    }

    /** Executes a submitter and waits for an external callback using a {@link TypeToken}, blocking until complete. */
    default <T> T waitForCallback(String name, TypeToken<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name, resultType, func, WaitForCallbackConfig.builder().build())
                .get();
    }

    /** Executes a submitter and waits for an external callback with custom configuration, blocking until complete. */
    default <T> T waitForCallback(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), func, waitForCallbackConfig)
                .get();
    }

    /** Executes a submitter and waits for an external callback using a {@link TypeToken} and custom configuration. */
    default <T> T waitForCallback(
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, resultType, func, waitForCallbackConfig)
                .get();
    }

    /** Asynchronously executes a submitter and waits for an external callback. */
    default <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name,
                TypeToken.get(resultType),
                func,
                WaitForCallbackConfig.builder().build());
    }

    /** Asynchronously executes a submitter and waits for an external callback using a {@link TypeToken}. */
    default <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name, resultType, func, WaitForCallbackConfig.builder().build());
    }

    /** Asynchronously executes a submitter and waits for an external callback with custom configuration. */
    default <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), func, waitForCallbackConfig);
    }

    /**
     * Asynchronously executes a submitter and waits for an external callback using a {@link TypeToken} and custom
     * configuration.
     *
     * <p>This is the core waitForCallbackAsync implementation. All other waitForCallback/waitForCallbackAsync overloads
     * delegate here. Internally creates a child context containing a callback operation and a step that runs the
     * submitter function.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic result types
     * @param func the submitter function, receiving the callback ID and a {@link StepContext}
     * @param waitForCallbackConfig the configuration for both the callback and submitter step
     * @return a future representing the callback result
     */
    <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig);

    /**
     * Polls a condition function until it signals done, blocking until complete.
     *
     * @param <T> the type of state being polled
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param checkFunc the function that evaluates the condition and returns a {@link WaitForConditionResult}
     * @param initialState the initial state passed to the first check invocation
     * @return the final state value when the condition is met
     */
    default <T> T waitForCondition(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState) {
        return waitForConditionAsync(
                        name,
                        TypeToken.get(resultType),
                        checkFunc,
                        initialState,
                        WaitForConditionConfig.<T>builder().build())
                .get();
    }

    /** Polls a condition function until it signals done, using a custom configuration, blocking until complete. */
    default <T> T waitForCondition(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, resultType, checkFunc, initialState, config)
                .get();
    }

    /** Polls a condition function until it signals done, using a {@link TypeToken}, blocking until complete. */
    default <T> T waitForCondition(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState) {
        return waitForConditionAsync(
                        name,
                        resultType,
                        checkFunc,
                        initialState,
                        WaitForConditionConfig.<T>builder().build())
                .get();
    }

    /**
     * Polls a condition function until it signals done, using a {@link TypeToken} and custom configuration, blocking
     * until complete.
     */
    default <T> T waitForCondition(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, resultType, checkFunc, initialState, config)
                .get();
    }

    /** Asynchronously polls a condition function until it signals done. */
    default <T> DurableFuture<T> waitForConditionAsync(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState) {
        return waitForConditionAsync(
                name,
                TypeToken.get(resultType),
                checkFunc,
                initialState,
                WaitForConditionConfig.<T>builder().build());
    }

    /** Asynchronously polls a condition function until it signals done, using custom configuration. */
    default <T> DurableFuture<T> waitForConditionAsync(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, TypeToken.get(resultType), checkFunc, initialState, config);
    }

    /** Asynchronously polls a condition function until it signals done, using a {@link TypeToken}. */
    default <T> DurableFuture<T> waitForConditionAsync(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState) {
        return waitForConditionAsync(
                name,
                resultType,
                checkFunc,
                initialState,
                WaitForConditionConfig.<T>builder().build());
    }

    /**
     * Asynchronously polls a condition function until it signals done, using a {@link TypeToken} and custom
     * configuration.
     *
     * <p>This is the core waitForConditionAsync implementation. All other waitForCondition/waitForConditionAsync
     * overloads delegate here.
     *
     * @param <T> the type of state being polled
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic types
     * @param checkFunc the function that evaluates the condition and returns a {@link WaitForConditionResult}
     * @param initialState the initial state passed to the first check invocation
     * @param config the waitForCondition configuration (wait strategy, custom SerDes)
     * @return a future representing the final state value
     */
    <T> DurableFuture<T> waitForConditionAsync(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState,
            WaitForConditionConfig<T> config);

    /**
     * Function applied to each item in a map operation.
     *
     * <p>Each invocation receives its own {@link DurableContext}, allowing the use of durable operations like
     * {@code step()} and {@code wait()} within the function body. The index parameter indicates the item's position in
     * the input collection.
     *
     * @param <I> the input item type
     * @param <O> the output result type
     */
    @FunctionalInterface
    interface MapFunction<I, O> {

        /**
         * Applies this function to the given item.
         *
         * @param item the input item to process
         * @param index the zero-based index of the item in the input collection
         * @param context the durable context for this item's execution
         * @return the result of processing the item
         */
        O apply(I item, int index, DurableContext context);
    }
}
