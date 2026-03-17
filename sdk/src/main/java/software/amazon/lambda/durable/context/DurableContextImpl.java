// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.CallbackConfig;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.InvokeConfig;
import software.amazon.lambda.durable.MapConfig;
import software.amazon.lambda.durable.MapFunction;
import software.amazon.lambda.durable.ParallelConfig;
import software.amazon.lambda.durable.ParallelContext;
import software.amazon.lambda.durable.StepConfig;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.WaitForCallbackConfig;
import software.amazon.lambda.durable.WaitForConditionConfig;
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
import software.amazon.lambda.durable.operation.ParallelOperation;
import software.amazon.lambda.durable.operation.StepOperation;
import software.amazon.lambda.durable.operation.WaitForConditionOperation;
import software.amazon.lambda.durable.operation.WaitOperation;
import software.amazon.lambda.durable.util.CompletedDurableFuture;
import software.amazon.lambda.durable.validation.ParameterValidator;

/**
 * User-facing API for defining durable operations within a workflow.
 *
 * <p>Provides methods for creating steps, waits, chained invokes, callbacks, and child contexts. Each method creates a
 * checkpoint-backed operation that survives Lambda interruptions.
 */
public class DurableContextImpl extends BaseContextImpl implements DurableContext {
    private static final String WAIT_FOR_CALLBACK_CALLBACK_SUFFIX = "-callback";
    private static final String WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX = "-submitter";
    private static final int MAX_WAIT_FOR_CALLBACK_NAME_LENGTH = ParameterValidator.MAX_OPERATION_NAME_LENGTH
            - Math.max(WAIT_FOR_CALLBACK_CALLBACK_SUFFIX.length(), WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX.length());
    private final OperationIdGenerator operationIdGenerator;
    private volatile DurableLogger logger;

    /** Shared initialization — sets all fields. */
    private DurableContextImpl(
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
    public static DurableContextImpl createRootContext(
            ExecutionManager executionManager, DurableConfig durableConfig, Context lambdaContext) {
        return new DurableContextImpl(executionManager, durableConfig, lambdaContext, null, null);
    }

    /**
     * Creates a child context.
     *
     * @param childContextId the child context's ID (the CONTEXT operation's operation ID)
     * @param childContextName the name of the child context
     * @return a new DurableContext for the child context
     */
    public DurableContextImpl createChildContext(String childContextId, String childContextName) {
        return new DurableContextImpl(
                getExecutionManager(), getDurableConfig(), getLambdaContext(), childContextId, childContextName);
    }

    /**
     * Creates a step context for executing step operations.
     *
     * @param stepOperationId the ID of the step operation (used for thread registration)
     * @param stepOperationName the name of the step operation
     * @param attempt the current retry attempt number (0-based)
     * @return a new StepContext instance
     */
    public StepContextImpl createStepContext(String stepOperationId, String stepOperationName, int attempt) {
        return new StepContextImpl(
                getExecutionManager(),
                getDurableConfig(),
                getLambdaContext(),
                stepOperationId,
                stepOperationName,
                attempt);
    }

    // ========== step methods ==========

    @Override
    public <T> T step(String name, Class<T> resultType, Function<StepContext, T> func) {
        return step(name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    @Override
    public <T> T step(String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, resultType, func, config).get();
    }

    @Override
    public <T> T step(String name, TypeToken<T> typeToken, Function<StepContext, T> func) {
        return step(name, typeToken, func, StepConfig.builder().build());
    }

    @Override
    public <T> T step(String name, TypeToken<T> typeToken, Function<StepContext, T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, typeToken, func, config).get();
    }

    @Override
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Function<StepContext, T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    @Override
    public <T> DurableFuture<T> stepAsync(
            String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    @Override
    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Function<StepContext, T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build());
    }

    @Override
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
    @Override
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
    @Override
    public <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, TypeToken.get(resultType), func, config).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    @Override
    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build()).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    @Override
    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, typeToken, func, config).get();
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    @Override
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    @Override
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    @Override
    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build());
    }

    /** @deprecated use the variants accepting StepContext instead */
    @Deprecated
    @Override
    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        return stepAsync(name, typeToken, stepContext -> func.get(), config);
    }

    // ========== wait methods ==========

    @Override
    public Void wait(String name, Duration duration) {
        // Block (will throw SuspendExecutionException if there is no active thread)
        return waitAsync(name, duration).get();
    }

    @Override
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

    @Override
    public <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        TypeToken.get(resultType),
                        InvokeConfig.builder().build())
                .get();
    }

    @Override
    public <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config)
                .get();
    }

    @Override
    public <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> typeToken) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        typeToken,
                        InvokeConfig.builder().build())
                .get();
    }

    @Override
    public <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> typeToken, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, typeToken, config).get();
    }

    /** Asynchronously invokes another Lambda function with custom configuration. */
    @Override
    public <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config);
    }

    @Override
    public <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                name,
                functionName,
                payload,
                TypeToken.get(resultType),
                InvokeConfig.builder().build());
    }

    @Override
    public <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, TypeToken<T> resultType) {
        return invokeAsync(
                name, functionName, payload, resultType, InvokeConfig.builder().build());
    }

    @Override
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

    @Override
    public <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType, CallbackConfig config) {
        return createCallback(name, TypeToken.get(resultType), config);
    }

    @Override
    public <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> typeToken) {
        return createCallback(name, typeToken, CallbackConfig.builder().build());
    }

    @Override
    public <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType) {
        return createCallback(
                name, TypeToken.get(resultType), CallbackConfig.builder().build());
    }

    @Override
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

    @Override
    public <T> T runInChildContext(String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func).get();
    }

    @Override
    public <T> T runInChildContext(String name, TypeToken<T> typeToken, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, typeToken, func).get();
    }

    @Override
    public <T> DurableFuture<T> runInChildContextAsync(
            String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func);
    }

    @Override
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

    @Override
    public <I, O> MapResult<O> map(String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function) {
        return mapAsync(
                        name,
                        items,
                        TypeToken.get(resultType),
                        function,
                        MapConfig.builder().build())
                .get();
    }

    @Override
    public <I, O> MapResult<O> map(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, TypeToken.get(resultType), function, config)
                .get();
    }

    @Override
    public <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function) {
        return mapAsync(name, items, resultType, function, MapConfig.builder().build())
                .get();
    }

    @Override
    public <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, resultType, function, config).get();
    }

    @Override
    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function) {
        return mapAsync(
                name,
                items,
                TypeToken.get(resultType),
                function,
                MapConfig.builder().build());
    }

    @Override
    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return mapAsync(name, items, TypeToken.get(resultType), function, config);
    }

    @Override
    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function) {
        return mapAsync(name, items, resultType, function, MapConfig.builder().build());
    }

    @Override
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

    // ========== parallel methods ==========

    @Override
    public ParallelContext parallel(String name, ParallelConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        var operationId = nextOperationId();

        var parallelOp = new ParallelOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.PARALLEL),
                TypeToken.get(Void.class),
                getDurableConfig().getSerDes(),
                this,
                config.maxConcurrency(),
                config.minSuccessful(),
                config.toleratedFailureCount());

        parallelOp.execute();

        return new ParallelContext(parallelOp, this);
    }

    // ========= waitForCallback methods =============

    @Override
    public <T> T waitForCallback(String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name,
                        TypeToken.get(resultType),
                        func,
                        WaitForCallbackConfig.builder().build())
                .get();
    }

    @Override
    public <T> T waitForCallback(String name, TypeToken<T> typeToken, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name, typeToken, func, WaitForCallbackConfig.builder().build())
                .get();
    }

    @Override
    public <T> T waitForCallback(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), func, waitForCallbackConfig)
                .get();
    }

    @Override
    public <T> T waitForCallback(
            String name,
            TypeToken<T> typeToken,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, typeToken, func, waitForCallbackConfig)
                .get();
    }

    @Override
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name,
                TypeToken.get(resultType),
                func,
                WaitForCallbackConfig.builder().build());
    }

    @Override
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> typeToken, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name, typeToken, func, WaitForCallbackConfig.builder().build());
    }

    @Override
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), func, waitForCallbackConfig);
    }

    @Override
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

    // ========== waitForCondition methods ==========
    @Override
    public <T> T waitForCondition(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, T> checkFunc,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, TypeToken.get(resultType), checkFunc, config)
                .get();
    }

    @Override
    public <T> T waitForCondition(
            String name,
            TypeToken<T> typeToken,
            BiFunction<T, StepContext, T> checkFunc,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, typeToken, checkFunc, config).get();
    }

    @Override
    public <T> DurableFuture<T> waitForConditionAsync(
            String name,
            Class<T> resultType,
            BiFunction<T, StepContext, T> checkFunc,
            WaitForConditionConfig<T> config) {
        return waitForConditionAsync(name, TypeToken.get(resultType), checkFunc, config);
    }

    @Override
    public <T> DurableFuture<T> waitForConditionAsync(
            String name,
            TypeToken<T> typeToken,
            BiFunction<T, StepContext, T> checkFunc,
            WaitForConditionConfig<T> config) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        ParameterValidator.validateOperationName(name);

        if (config.serDes() == null) {
            config = WaitForConditionConfig.<T>builder(config.waitStrategy(), config.initialState())
                    .serDes(getDurableConfig().getSerDes())
                    .build();
        }
        var operationId = nextOperationId();

        var operation = new WaitForConditionOperation<>(operationId, name, checkFunc, typeToken, config, this);

        operation.execute();

        return operation;
    }

    // =============== accessors ================
    @Override
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
