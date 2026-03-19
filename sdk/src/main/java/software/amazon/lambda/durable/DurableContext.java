// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.time.Duration;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import software.amazon.lambda.durable.model.MapResult;

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
    <T> T step(String name, Class<T> resultType, Function<StepContext, T> func);

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
    <T> T step(String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config);

    /**
     * Executes a durable step using a {@link TypeToken} for generic result types, blocking until it completes.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @return the step result
     */
    <T> T step(String name, TypeToken<T> resultType, Function<StepContext, T> func);

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
    <T> T step(String name, TypeToken<T> resultType, Function<StepContext, T> func, StepConfig config);

    /**
     * Asynchronously executes a durable step, returning a {@link DurableFuture} that can be composed or blocked on.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param resultType the result class for deserialization
     * @param func the function to execute, receiving a {@link StepContext}
     * @return a future representing the step result
     */
    <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Function<StepContext, T> func);

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
    <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config);

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
    <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Function<StepContext, T> func);

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

    @Deprecated
    <T> T step(String name, Class<T> resultType, Supplier<T> func);

    @Deprecated
    <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config);

    @Deprecated
    <T> T step(String name, TypeToken<T> resultType, Supplier<T> func);

    @Deprecated
    <T> T step(String name, TypeToken<T> resultType, Supplier<T> func, StepConfig config);

    @Deprecated
    <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func);

    @Deprecated
    <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config);

    @Deprecated
    <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Supplier<T> func);

    @Deprecated
    <T> DurableFuture<T> stepAsync(String name, TypeToken<T> resultType, Supplier<T> func, StepConfig config);

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
    Void wait(String name, Duration duration);

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
    <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType);

    /** Invokes another Lambda function with custom configuration, blocking until the result is available. */
    <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType, InvokeConfig config);

    /** Invokes another Lambda function using a {@link TypeToken} for generic result types, blocking until complete. */
    <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> resultType);

    /** Invokes another Lambda function using a {@link TypeToken} and custom configuration, blocking until complete. */
    <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config);

    /** Invokes another Lambda function using a {@link TypeToken} and custom configuration, blocking until complete. */
    <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config);

    /** Asynchronously invokes another Lambda function, returning a {@link DurableFuture}. */
    <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, Class<T> resultType);

    /** Asynchronously invokes another Lambda function using a {@link TypeToken} for generic result types. */
    <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, TypeToken<T> resultType);

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
    <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType, CallbackConfig config);

    /** Creates a callback using a {@link TypeToken} for generic result types. */
    <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> resultType);

    /** Creates a callback with default configuration. */
    <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType);

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
    <T> T runInChildContext(String name, Class<T> resultType, Function<DurableContext, T> func);

    /**
     * Runs a function in a child context using a {@link TypeToken} for generic result types, blocking until complete.
     */
    <T> T runInChildContext(String name, TypeToken<T> resultType, Function<DurableContext, T> func);

    /** Asynchronously runs a function in a child context, returning a {@link DurableFuture}. */
    <T> DurableFuture<T> runInChildContextAsync(String name, Class<T> resultType, Function<DurableContext, T> func);

    /** Asynchronously runs a function in a child context using a {@link TypeToken} for generic result types. */
    <T> DurableFuture<T> runInChildContextAsync(String name, TypeToken<T> resultType, Function<DurableContext, T> func);

    <I, O> MapResult<O> map(String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function);

    <I, O> MapResult<O> map(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config);

    <I, O> MapResult<O> map(String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function);

    <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config);

    <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function);

    <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config);

    <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function);

    <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config);

    /**
     * Creates a {@link ParallelContext} for executing multiple branches concurrently.
     *
     * @param config the parallel execution configuration
     * @return a new ParallelContext for registering and executing branches
     */
    ParallelContext parallel(String name, ParallelConfig config);

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
    <T> T waitForCallback(String name, Class<T> resultType, BiConsumer<String, StepContext> func);

    /** Executes a submitter and waits for an external callback using a {@link TypeToken}, blocking until complete. */
    <T> T waitForCallback(String name, TypeToken<T> resultType, BiConsumer<String, StepContext> func);

    /** Executes a submitter and waits for an external callback with custom configuration, blocking until complete. */
    <T> T waitForCallback(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig);

    /** Executes a submitter and waits for an external callback using a {@link TypeToken} and custom configuration. */
    <T> T waitForCallback(
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig);

    /** Asynchronously executes a submitter and waits for an external callback. */
    <T> DurableFuture<T> waitForCallbackAsync(String name, Class<T> resultType, BiConsumer<String, StepContext> func);

    /** Asynchronously executes a submitter and waits for an external callback using a {@link TypeToken}. */
    <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> resultType, BiConsumer<String, StepContext> func);

    /** Asynchronously executes a submitter and waits for an external callback with custom configuration. */
    <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig);

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
    <T> T waitForCondition(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState);

    /** Polls a condition function until it signals done, using a custom configuration, blocking until complete. */
    <T> T waitForCondition(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState,
            WaitForConditionConfig<T> config);

    /** Polls a condition function until it signals done, using a {@link TypeToken}, blocking until complete. */
    <T> T waitForCondition(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState);

    /**
     * Polls a condition function until it signals done, using a {@link TypeToken} and custom configuration, blocking
     * until complete.
     */
    <T> T waitForCondition(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState,
            WaitForConditionConfig<T> config);

    /** Asynchronously polls a condition function until it signals done. */
    <T> DurableFuture<T> waitForConditionAsync(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState);

    /** Asynchronously polls a condition function until it signals done, using custom configuration. */
    <T> DurableFuture<T> waitForConditionAsync(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState,
            WaitForConditionConfig<T> config);

    /** Asynchronously polls a condition function until it signals done, using a {@link TypeToken}. */
    <T> DurableFuture<T> waitForConditionAsync(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            T initialState);

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
}
