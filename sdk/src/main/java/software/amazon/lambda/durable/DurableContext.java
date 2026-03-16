// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.CallbackOperation;
import software.amazon.lambda.durable.operation.ChildContextOperation;
import software.amazon.lambda.durable.operation.InvokeOperation;
import software.amazon.lambda.durable.operation.MapOperation;
import software.amazon.lambda.durable.operation.StepOperation;
import software.amazon.lambda.durable.operation.WaitOperation;
import software.amazon.lambda.durable.validation.ParameterValidator;

/**
 * User-facing API for defining durable operations within a workflow.
 *
 * <p>Provides methods for creating steps, waits, chained invokes, callbacks, and child contexts. Each method creates a
 * checkpoint-backed operation that survives Lambda interruptions.
 */
public class DurableContext extends BaseContext {
    private static final String WAIT_FOR_CALLBACK_CALLBACK_SUFFIX = "-callback";
    private static final String WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX = "-submitter";
    private static final int MAX_WAIT_FOR_CALLBACK_NAME_LENGTH = ParameterValidator.MAX_OPERATION_NAME_LENGTH
            - Math.max(WAIT_FOR_CALLBACK_CALLBACK_SUFFIX.length(), WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX.length());
    private final OperationIdGenerator operationIdGenerator;
    private volatile DurableLogger logger;

    /** Shared initialization — sets all fields. */
    private DurableContext(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String contextId,
            String contextName) {
        super(executionManager, durableConfig, lambdaContext, contextId, contextName, ThreadType.CONTEXT);
        operationIdGenerator = new OperationIdGenerator(contextId);
    }

    /**
     * Creates a root context (contextId = null)
     *
     * <p>The context itself always has a null contextId (making it a root context).
     *
     * @param executionManager the execution manager
     * @param durableConfig the durable configuration
     * @param lambdaContext the Lambda context
     * @return a new root DurableContext
     */
    public static DurableContext createRootContext(
            ExecutionManager executionManager, DurableConfig durableConfig, Context lambdaContext) {
        return new DurableContext(executionManager, durableConfig, lambdaContext, null, null);
    }

    /**
     * Creates a child context.
     *
     * @param childContextId the child context's ID (the CONTEXT operation's operation ID)
     * @param childContextName the name of the child context
     * @return a new DurableContext for the child context
     */
    public DurableContext createChildContext(String childContextId, String childContextName) {
        return new DurableContext(
                getExecutionManager(), getDurableConfig(), getLambdaContext(), childContextId, childContextName);
    }

    /**
     * Creates a step context for executing step operations.
     *
     * @param stepOperationId the ID of the step operation (used for thread registration)
     * @param stepOperationName the name of the step operation
     * @param attempt the current retry attempt number (1-based)
     * @return a new StepContext instance
     */
    public StepContext createStepContext(String stepOperationId, String stepOperationName, int attempt) {
        return new StepContext(
                getExecutionManager(),
                getDurableConfig(),
                getLambdaContext(),
                stepOperationId,
                stepOperationName,
                attempt);
    }

    // ========== step methods ==========

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
    public <T> T step(String name, Class<T> resultType, Function<StepContext, T> func) {
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
    public <T> T step(String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, resultType, func, config).get();
    }

    /**
     * Executes a durable step using a {@link TypeToken} for generic result types, blocking until it completes.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param typeToken the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @return the step result
     */
    public <T> T step(String name, TypeToken<T> typeToken, Function<StepContext, T> func) {
        return step(name, typeToken, func, StepConfig.builder().build());
    }

    /**
     * Executes a durable step using a {@link TypeToken} and configuration, blocking until it completes.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param typeToken the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @param config the step configuration (retry strategy, semantics, custom SerDes)
     * @return the step result
     */
    public <T> T step(String name, TypeToken<T> typeToken, Function<StepContext, T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, typeToken, func, config).get();
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
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Function<StepContext, T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    /** Asynchronously executes a durable step with custom configuration. */
    public <T> DurableFuture<T> stepAsync(
            String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    /** Asynchronously executes a durable step using a {@link TypeToken} for generic result types. */
    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Function<StepContext, T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build());
    }

    /**
     * Asynchronously executes a durable step using a {@link TypeToken} and custom configuration.
     *
     * <p>This is the core stepAsync implementation. All other step/stepAsync overloads delegate here.
     *
     * @param <T> the result type
     * @param name the unique operation name within this context
     * @param typeToken the type token for deserialization of generic types
     * @param func the function to execute, receiving a {@link StepContext}
     * @param config the step configuration (retry strategy, semantics, custom SerDes)
     * @return a future representing the step result
     */
    public <T> DurableFuture<T> stepAsync(
            String name, TypeToken<T> typeToken, Function<StepContext, T> func, StepConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        ParameterValidator.validateOperationName(name);

        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }
        var operationId = nextOperationId();

        // Create and start step operation with TypeToken
        var operation = new StepOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.STEP), func, typeToken, config, this);

        operation.execute(); // Start the step (returns immediately)

        return operation;
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(
                        name,
                        TypeToken.get(resultType),
                        func,
                        StepConfig.builder().build())
                .get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, TypeToken.get(resultType), func, config).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build()).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, typeToken, func, config).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build());
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        return stepAsync(name, typeToken, stepContext -> func.get(), config);
    }

    // ========== wait methods ==========

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
    public Void wait(String name, Duration duration) {
        // Block (will throw SuspendExecutionException if there is no active thread)
        return waitAsync(name, duration).get();
    }

    /**
     * Asynchronously suspends execution for the specified duration.
     *
     * @param name the unique operation name within this context
     * @param duration the duration to wait
     * @return a future that completes when the wait duration has elapsed
     */
    public DurableFuture<Void> waitAsync(String name, Duration duration) {
        ParameterValidator.validateDuration(duration, "Wait duration");
        ParameterValidator.validateOperationName(name);

        var operationId = nextOperationId();

        // Create and start wait operation
        var operation =
                new WaitOperation(OperationIdentifier.of(operationId, name, OperationType.WAIT), duration, this);

        operation.execute(); // Checkpoint the wait
        return operation;
    }

    // ========== chained invoke methods ==========

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
    public <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        TypeToken.get(resultType),
                        InvokeConfig.builder().build())
                .get();
    }

    /** Invokes another Lambda function with custom configuration, blocking until the result is available. */
    public <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config)
                .get();
    }

    /** Invokes another Lambda function using a {@link TypeToken} for generic result types, blocking until complete. */
    public <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> typeToken) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        typeToken,
                        InvokeConfig.builder().build())
                .get();
    }

    /** Invokes another Lambda function using a {@link TypeToken} and custom configuration, blocking until complete. */
    public <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> typeToken, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, typeToken, config).get();
    }

    /** Asynchronously invokes another Lambda function with custom configuration. */
    public <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config);
    }

    /** Asynchronously invokes another Lambda function, returning a {@link DurableFuture}. */
    public <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                name,
                functionName,
                payload,
                TypeToken.get(resultType),
                InvokeConfig.builder().build());
    }

    /** Asynchronously invokes another Lambda function using a {@link TypeToken} for generic result types. */
    public <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, TypeToken<T> resultType) {
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
     * @param typeToken the type token for deserialization of generic result types
     * @param config the invoke configuration (custom SerDes for result and payload)
     * @return a future representing the invocation result
     */
    public <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, TypeToken<T> typeToken, InvokeConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        ParameterValidator.validateOperationName(name);

        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }
        if (config.payloadSerDes() == null) {
            config = config.toBuilder()
                    .payloadSerDes(getDurableConfig().getSerDes())
                    .build();
        }
        var operationId = nextOperationId();

        // Create and start invoke operation
        var operation = new InvokeOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CHAINED_INVOKE),
                functionName,
                payload,
                typeToken,
                config,
                this);

        operation.execute(); // checkpoint the invoke operation
        return operation; // Block (will throw SuspendExecutionException if needed)
    }

    // ========== createCallback methods ==========

    /** Creates a callback with custom configuration. */
    public <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType, CallbackConfig config) {
        return createCallback(name, TypeToken.get(resultType), config);
    }

    /** Creates a callback using a {@link TypeToken} for generic result types. */
    public <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> typeToken) {
        return createCallback(name, typeToken, CallbackConfig.builder().build());
    }

    /** Creates a callback with default configuration. */
    public <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType) {
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
     * @param typeToken the type token for deserialization of generic result types
     * @param config the callback configuration (custom SerDes)
     * @return a future containing the callback ID and eventual result
     */
    public <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> typeToken, CallbackConfig config) {
        ParameterValidator.validateOperationName(name);
        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }
        var operationId = nextOperationId();

        var operation = new CallbackOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CALLBACK), typeToken, config, this);
        operation.execute();

        return operation;
    }

    // ========== runInChildContext methods ==========

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
    public <T> T runInChildContext(String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func).get();
    }

    /**
     * Runs a function in a child context using a {@link TypeToken} for generic result types, blocking until complete.
     */
    public <T> T runInChildContext(String name, TypeToken<T> typeToken, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, typeToken, func).get();
    }

    /** Asynchronously runs a function in a child context, returning a {@link DurableFuture}. */
    public <T> DurableFuture<T> runInChildContextAsync(
            String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func);
    }

    /** Asynchronously runs a function in a child context using a {@link TypeToken} for generic result types. */
    public <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> typeToken, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, typeToken, func, OperationSubType.RUN_IN_CHILD_CONTEXT);
    }

    private <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> typeToken, Function<DurableContext, T> func, OperationSubType subType) {
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        ParameterValidator.validateOperationName(name);
        var operationId = nextOperationId();

        var operation = new ChildContextOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, subType),
                func,
                typeToken,
                getDurableConfig().getSerDes(),
                this);

        operation.execute();
        return operation;
    }

    // ========== map methods ==========

    public <I, O> MapResult<O> map(String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function) {
        return mapAsync(
                        name,
                        items,
                        TypeToken.get(resultType),
                        function,
                        MapConfig.builder().build())
                .get();
    }

    public <I, O> MapResult<O> map(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, TypeToken.get(resultType), function, config)
                .get();
    }

    public <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function) {
        return mapAsync(name, items, resultType, function, MapConfig.builder().build())
                .get();
    }

    public <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, resultType, function, config).get();
    }

    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function) {
        return mapAsync(
                name,
                items,
                TypeToken.get(resultType),
                function,
                MapConfig.builder().build());
    }

    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, TypeToken.get(resultType), function, config);
    }

    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function) {
        return mapAsync(name, items, resultType, function, MapConfig.builder().build());
    }

    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config) {
        Objects.requireNonNull(items, "items cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);
        ParameterValidator.validateOrderedCollection(items);

        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }

        // Short-circuit for empty collections — no checkpoint overhead
        if (items.isEmpty()) {
            return new CompletedDurableFuture<>(MapResult.empty());
        }

        // Convert to List for deterministic index-based access
        var itemList = List.copyOf(items);
        var operationId = nextOperationId();

        var operation = new MapOperation<>(operationId, name, itemList, function, resultType, config, this);
        operation.execute();
        return operation;
    }

    // ========= waitForCallback methods =============

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
    public <T> T waitForCallback(String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name,
                        TypeToken.get(resultType),
                        func,
                        WaitForCallbackConfig.builder().build())
                .get();
    }

    /** Executes a submitter and waits for an external callback using a {@link TypeToken}, blocking until complete. */
    public <T> T waitForCallback(String name, TypeToken<T> typeToken, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name, typeToken, func, WaitForCallbackConfig.builder().build())
                .get();
    }

    /** Executes a submitter and waits for an external callback with custom configuration, blocking until complete. */
    public <T> T waitForCallback(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), func, waitForCallbackConfig)
                .get();
    }

    /** Executes a submitter and waits for an external callback using a {@link TypeToken} and custom configuration. */
    public <T> T waitForCallback(
            String name,
            TypeToken<T> typeToken,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, typeToken, func, waitForCallbackConfig)
                .get();
    }

    /** Asynchronously executes a submitter and waits for an external callback. */
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name,
                TypeToken.get(resultType),
                func,
                WaitForCallbackConfig.builder().build());
    }

    /** Asynchronously executes a submitter and waits for an external callback using a {@link TypeToken}. */
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> typeToken, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name, typeToken, func, WaitForCallbackConfig.builder().build());
    }

    /** Asynchronously executes a submitter and waits for an external callback with custom configuration. */
    public <T> DurableFuture<T> waitForCallbackAsync(
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
     * @param typeToken the type token for deserialization of generic result types
     * @param func the submitter function, receiving the callback ID and a {@link StepContext}
     * @param waitForCallbackConfig the configuration for both the callback and submitter step
     * @return a future representing the callback result
     */
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            TypeToken<T> typeToken,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        Objects.requireNonNull(waitForCallbackConfig, "waitForCallbackConfig cannot be null");
        // waitForCallback adds a suffix for the callback operation name and the submitter operation name so
        // the length restriction of waitForCallback name is different from the other operations.
        ParameterValidator.validateOperationName(name, MAX_WAIT_FOR_CALLBACK_NAME_LENGTH);

        var finalWaitForCallbackConfig = waitForCallbackConfig.stepConfig().serDes() == null
                ? waitForCallbackConfig.toBuilder()
                        .stepConfig(waitForCallbackConfig.stepConfig().toBuilder()
                                .serDes(getDurableConfig().getSerDes())
                                .build())
                        .build()
                : waitForCallbackConfig;

        return runInChildContextAsync(
                name,
                typeToken,
                childCtx -> {
                    var callback = childCtx.createCallback(
                            name + WAIT_FOR_CALLBACK_CALLBACK_SUFFIX,
                            typeToken,
                            finalWaitForCallbackConfig.callbackConfig());
                    childCtx.step(
                            name + WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX,
                            Void.class,
                            stepCtx -> {
                                func.accept(callback.callbackId(), stepCtx);
                                return null;
                            },
                            finalWaitForCallbackConfig.stepConfig());
                    return callback.get();
                },
                OperationSubType.WAIT_FOR_CALLBACK);
    }

    // =============== accessors ================
    /**
     * Returns a logger with execution context information for replay-aware logging.
     *
     * @return the durable logger
     */
    public DurableLogger getLogger() {
        // lazy initialize logger
        if (logger == null) {
            synchronized (this) {
                if (logger == null) {
                    logger = new DurableLogger(LoggerFactory.getLogger(DurableContext.class), this);
                }
            }
        }
        return logger;
    }

    /**
     * Clears the logger's thread properties. Called during context destruction to prevent memory leaks and ensure clean
     * state for subsequent executions.
     */
    @Override
    public void close() {
        if (logger != null) {
            logger.close();
        }
        super.close();
    }

    /**
     * Get the next operationId. Returns a globally unique operation ID by hashing a sequential operation counter. For
     * root contexts, the counter value is hashed directly (e.g. "1", "2", "3"). For child contexts, the values are
     * prefixed with the parent hashed contextId (e.g. "<hash>-1", "<hash>-2" inside parent context <hash>). This
     * matches the Python SDK's stepPrefix convention and prevents ID collisions in checkpoint batches.
     */
    private String nextOperationId() {
        return operationIdGenerator.nextOperationId();
    }
}
