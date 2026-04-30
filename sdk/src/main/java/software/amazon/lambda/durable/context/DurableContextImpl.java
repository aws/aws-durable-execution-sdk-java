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
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.model.WithRetry;
import software.amazon.lambda.durable.operation.CallbackOperation;
import software.amazon.lambda.durable.operation.ChildContextOperation;
import software.amazon.lambda.durable.operation.InvokeOperation;
import software.amazon.lambda.durable.operation.MapOperation;
import software.amazon.lambda.durable.operation.ParallelOperation;
import software.amazon.lambda.durable.operation.StepOperation;
import software.amazon.lambda.durable.operation.WaitForConditionOperation;
import software.amazon.lambda.durable.operation.WaitOperation;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.util.ParameterValidator;

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
    private final DurableContextImpl parentContext;
    private final boolean isVirtual;
    private volatile DurableLogger logger;

    /** Shared initialization — sets all fields. */
    private DurableContextImpl(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String contextId,
            String contextName,
            boolean isVirtual,
            DurableContextImpl parentContext) {
        super(executionManager, durableConfig, lambdaContext, contextId, contextName, ThreadType.CONTEXT);
        operationIdGenerator = new OperationIdGenerator(contextId);
        this.parentContext = parentContext;
        this.isVirtual = isVirtual;
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
        return new DurableContextImpl(executionManager, durableConfig, lambdaContext, null, null, false, null);
    }

    /**
     * Creates a child context.
     *
     * @param childContextId the child context's ID (the CONTEXT operation's operation ID)
     * @param childContextName the name of the child context
     * @param isVirtual whether the context is virtual
     * @return a new DurableContext for the child context
     */
    public DurableContextImpl createChildContext(String childContextId, String childContextName, boolean isVirtual) {
        return new DurableContextImpl(
                getExecutionManager(),
                getDurableConfig(),
                getLambdaContext(),
                childContextId,
                childContextName,
                isVirtual,
                this);
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

    @Override
    public <T> DurableFuture<T> stepAsync(
            String name, TypeToken<T> resultType, Function<StepContext, T> func, StepConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        ParameterValidator.validateOperationName(name);

        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }
        var operationId = nextOperationId();

        // Create and start step operation with TypeToken
        var operation = new StepOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.STEP), func, resultType, config, this);

        operation.execute(); // Start the step (returns immediately)

        return operation;
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

    @Override
    public <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
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
                resultType,
                config,
                this);

        operation.execute(); // checkpoint the invoke operation
        return operation; // Block (will throw SuspendExecutionException if needed)
    }

    @Override
    public <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> resultType, CallbackConfig config) {
        ParameterValidator.validateOperationName(name);
        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }
        var operationId = nextOperationId();

        var operation = new CallbackOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CALLBACK), resultType, config, this);
        operation.execute();

        return operation;
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
    @Override
    public <T> DurableFuture<T> runInChildContextAsync(
            String name, TypeToken<T> resultType, Function<DurableContext, T> func, RunInChildContextConfig config) {
        return runInChildContextAsync(name, resultType, func, config, OperationSubType.RUN_IN_CHILD_CONTEXT);
    }

    private <T> DurableFuture<T> runInChildContextAsync(
            String name,
            TypeToken<T> resultType,
            Function<DurableContext, T> func,
            RunInChildContextConfig config,
            OperationSubType subType) {
        return runInChildContextAsync(name, resultType, func, config, subType, false);
    }

    private <T> DurableFuture<T> runInVirtualChildContextAsync(
            String name,
            TypeToken<T> resultType,
            Function<DurableContext, T> func,
            RunInChildContextConfig config,
            OperationSubType subType) {
        return runInChildContextAsync(name, resultType, func, config, subType, true);
    }

    private <T> DurableFuture<T> runInChildContextAsync(
            String name,
            TypeToken<T> resultType,
            Function<DurableContext, T> func,
            RunInChildContextConfig config,
            OperationSubType subType,
            boolean isVirtual) {
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "RunInChildContextConfig cannot be null");
        ParameterValidator.validateOperationName(name);

        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }

        var operationId = nextOperationId();

        var operation = new ChildContextOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, subType),
                func,
                resultType,
                config,
                this,
                isVirtual,
                null);

        operation.execute();
        return operation;
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

        // Convert to List for deterministic index-based access
        var itemList = List.copyOf(items);
        var operationId = nextOperationId();

        var operation = new MapOperation<>(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.MAP),
                itemList,
                function,
                resultType,
                config,
                this);
        operation.execute();
        return operation;
    }

    @Override
    public ParallelDurableFuture parallel(String name, ParallelConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        var operationId = nextOperationId();

        var parallelOp = new ParallelOperation(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, OperationSubType.PARALLEL),
                getDurableConfig().getSerDes(),
                this,
                config);

        parallelOp.execute();

        return parallelOp;
    }

    @Override
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        Objects.requireNonNull(resultType, "resultType cannot be null");
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
                resultType,
                childCtx -> {
                    var callback = childCtx.createCallback(
                            name + WAIT_FOR_CALLBACK_CALLBACK_SUFFIX,
                            resultType,
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
                RunInChildContextConfig.builder()
                        .serDes(finalWaitForCallbackConfig.stepConfig().serDes())
                        .build(),
                OperationSubType.WAIT_FOR_CALLBACK);
    }

    @Override
    public <T> DurableFuture<T> waitForConditionAsync(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            WaitForConditionConfig<T> config) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(checkFunc, "checkFunc cannot be null");
        ParameterValidator.validateOperationName(name);

        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }
        var operationId = nextOperationId();

        var operation = new WaitForConditionOperation<>(operationId, name, checkFunc, resultType, config, this);

        operation.execute();

        return operation;
    }

    // =============== withRetry ================

    private static final Duration DEFAULT_BACKOFF_DELAY = Duration.ofSeconds(1);
    private static final String BACKOFF_SUFFIX = "-backoff-";
    private static final String ANONYMOUS_CHILD_CONTEXT_NAME = "retry";
    private static final String ANONYMOUS_BACKOFF_PREFIX = "retry-backoff-";

    @Override
    @SuppressWarnings("unchecked")
    public <T> DurableFuture<T> withRetryAsync(String name, WithRetry<T> operation, WithRetryConfig config) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (config.wrapInChildContext()) {
            return (DurableFuture<T>) runInChildContextAsync(
                    name,
                    new TypeToken<Object>() {},
                    childCtx -> executeRetryLoop(childCtx, name, operation, config),
                    RunInChildContextConfig.builder().build(),
                    OperationSubType.WITH_RETRY);
        }
        return (DurableFuture<T>) runInVirtualChildContextAsync(
                name,
                new TypeToken<Object>() {},
                childCtx -> executeRetryLoop(childCtx, name, operation, config),
                RunInChildContextConfig.builder().build(),
                OperationSubType.WITH_RETRY);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> DurableFuture<T> withRetryAsync(WithRetry<T> operation, WithRetryConfig config) {
        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (config.wrapInChildContext()) {
            return (DurableFuture<T>) runInChildContextAsync(
                    ANONYMOUS_CHILD_CONTEXT_NAME,
                    new TypeToken<Object>() {},
                    childCtx -> executeRetryLoop(childCtx, null, operation, config),
                    RunInChildContextConfig.builder().build(),
                    OperationSubType.WITH_RETRY);
        }
        return (DurableFuture<T>) runInVirtualChildContextAsync(
                ANONYMOUS_CHILD_CONTEXT_NAME,
                new TypeToken<Object>() {},
                childCtx -> executeRetryLoop(childCtx, null, operation, config),
                RunInChildContextConfig.builder().build(),
                OperationSubType.WITH_RETRY);
    }

    /**
     * Core retry loop. Replay-safe because every side-effect is a durable operation: the user's operation calls durable
     * primitives, and backoff uses {@code context.wait()}.
     *
     * <p>{@link SuspendExecutionException} and {@link UnrecoverableDurableExecutionException} are never retried — they
     * are internal SDK control flow signals that must propagate immediately.
     */
    private static <T> T executeRetryLoop(
            DurableContext context, String name, WithRetry<T> operation, WithRetryConfig config) {
        var attempt = 1;
        while (true) {
            try {
                return operation.execute(context, attempt);
            } catch (SuspendExecutionException | UnrecoverableDurableExecutionException e) {
                // Internal SDK control flow — never retry, always propagate
                throw e;
            } catch (Exception e) {
                RetryDecision decision = config.retryStrategy().makeRetryDecision(e, attempt);
                if (!decision.shouldRetry()) {
                    throw e;
                }

                var delay = decision.delay().isZero() ? DEFAULT_BACKOFF_DELAY : decision.delay();
                var waitName = name != null ? name + BACKOFF_SUFFIX + attempt : ANONYMOUS_BACKOFF_PREFIX + attempt;
                context.wait(waitName, delay);
                attempt++;
            }
        }
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

    /**
     * Get the parent context ID for its child operations, which always points to a non-virtual context
     *
     * @return the parent of this context if virtual, otherwise this context id
     */
    public String getParentId() {
        return isVirtual ? parentContext.getContextId() : getContextId();
    }
}
