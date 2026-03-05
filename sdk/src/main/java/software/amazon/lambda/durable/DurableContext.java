// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.CallbackOperation;
import software.amazon.lambda.durable.operation.ChildContextOperation;
import software.amazon.lambda.durable.operation.InvokeOperation;
import software.amazon.lambda.durable.operation.StepOperation;
import software.amazon.lambda.durable.operation.WaitOperation;
import software.amazon.lambda.durable.validation.ParameterValidator;

public class DurableContext extends BaseContext {
    private static final String WAIT_FOR_CALLBACK_CALLBACK_SUFFIX = "-callback";
    private static final String WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX = "-submitter";
    private static final int MAX_WAIT_FOR_CALLBACK_NAME_LENGTH = ParameterValidator.MAX_OPERATION_NAME_LENGTH
            - Math.max(WAIT_FOR_CALLBACK_CALLBACK_SUFFIX.length(), WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX.length());
    private final AtomicInteger operationCounter;
    private volatile DurableLogger logger;

    /** Shared initialization — sets all fields. */
    private DurableContext(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String contextId,
            String contextName) {
        super(executionManager, durableConfig, lambdaContext, contextId, contextName, ThreadType.CONTEXT);
        this.operationCounter = new AtomicInteger(0);
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

    public <T> T step(String name, Class<T> resultType, Function<StepContext, T> func) {
        return step(name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    public <T> T step(String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, resultType, func, config).get();
    }

    public <T> T step(String name, TypeToken<T> typeToken, Function<StepContext, T> func) {
        return step(name, typeToken, func, StepConfig.builder().build());
    }

    public <T> T step(String name, TypeToken<T> typeToken, Function<StepContext, T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, typeToken, func, config).get();
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Function<StepContext, T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    public <T> DurableFuture<T> stepAsync(
            String name, Class<T> resultType, Function<StepContext, T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Function<StepContext, T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build());
    }

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
        var operation = new StepOperation<>(operationId, name, func, typeToken, config, this);

        operation.execute(); // Start the step (returns immediately)

        return operation;
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        return step(name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, resultType, func, config).get();
    }

    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return step(name, typeToken, func, StepConfig.builder().build());
    }

    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, typeToken, func, config).get();
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(
                name, TypeToken.get(resultType), func, StepConfig.builder().build());
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return stepAsync(name, typeToken, func, StepConfig.builder().build());
    }

    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        return stepAsync(name, typeToken, stepContext -> func.get(), config);
    }

    // ========== wait methods ==========

    public Void wait(String name, Duration duration) {
        // Block (will throw SuspendExecutionException if there is no active thread)
        return waitAsync(name, duration).get();
    }

    public DurableFuture<Void> waitAsync(String name, Duration duration) {
        ParameterValidator.validateDuration(duration, "Wait duration");
        ParameterValidator.validateOperationName(name);

        var operationId = nextOperationId();

        // Create and start wait operation
        var operation = new WaitOperation(operationId, name, duration, this);

        operation.execute(); // Checkpoint the wait
        return operation;
    }

    // ========== chained invoke methods ==========

    public <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        resultType,
                        InvokeConfig.builder().build())
                .get();
    }

    public <T, U> T invoke(String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config)
                .get();
    }

    public <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> typeToken) {
        return invokeAsync(
                        name,
                        functionName,
                        payload,
                        typeToken,
                        InvokeConfig.builder().build())
                .get();
    }

    public <T, U> T invoke(String name, String functionName, U payload, TypeToken<T> typeToken, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, typeToken, config).get();
    }

    public <T, U> DurableFuture<T> invokeAsync(
            String name, String functionName, U payload, Class<T> resultType, InvokeConfig config) {
        return invokeAsync(name, functionName, payload, TypeToken.get(resultType), config);
    }

    public <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, Class<T> resultType) {
        return invokeAsync(
                name,
                functionName,
                payload,
                TypeToken.get(resultType),
                InvokeConfig.builder().build());
    }

    public <T, U> DurableFuture<T> invokeAsync(String name, String functionName, U payload, TypeToken<T> resultType) {
        return invokeAsync(
                name, functionName, payload, resultType, InvokeConfig.builder().build());
    }

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
        var operation = new InvokeOperation<>(operationId, name, functionName, payload, typeToken, config, this);

        operation.execute(); // checkpoint the invoke operation
        return operation; // Block (will throw SuspendExecutionException if needed)
    }

    // ========== createCallback methods ==========

    public <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType, CallbackConfig config) {
        return createCallback(name, TypeToken.get(resultType), config);
    }

    public <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> typeToken) {
        return createCallback(name, typeToken, CallbackConfig.builder().build());
    }

    public <T> DurableCallbackFuture<T> createCallback(String name, Class<T> resultType) {
        return createCallback(name, resultType, CallbackConfig.builder().build());
    }

    public <T> DurableCallbackFuture<T> createCallback(String name, TypeToken<T> typeToken, CallbackConfig config) {
        ParameterValidator.validateOperationName(name);
        if (config.serDes() == null) {
            config = config.toBuilder().serDes(getDurableConfig().getSerDes()).build();
        }
        var operationId = nextOperationId();

        var operation = new CallbackOperation<>(operationId, name, typeToken, config, this);
        operation.execute();

        return operation;
    }

    // ========== runInChildContext methods ==========

    public <T> T runInChildContext(String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func).get();
    }

    public <T> T runInChildContext(String name, TypeToken<T> typeToken, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, typeToken, func).get();
    }

    public <T> DurableFuture<T> runInChildContextAsync(
            String name, Class<T> resultType, Function<DurableContext, T> func) {
        return runInChildContextAsync(name, TypeToken.get(resultType), func);
    }

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
                operationId, name, func, subType, typeToken, getDurableConfig().getSerDes(), this);

        operation.execute();
        return operation;
    }

    // ========= waitForCallback methods =============
    public <T> T waitForCallback(String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name,
                        TypeToken.get(resultType),
                        func,
                        WaitForCallbackConfig.builder().build())
                .get();
    }

    public <T> T waitForCallback(String name, TypeToken<T> typeToken, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                        name, typeToken, func, WaitForCallbackConfig.builder().build())
                .get();
    }

    public <T> T waitForCallback(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), func, waitForCallbackConfig)
                .get();
    }

    public <T> T waitForCallback(
            String name,
            TypeToken<T> typeToken,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, typeToken, func, waitForCallbackConfig)
                .get();
    }

    public <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name,
                TypeToken.get(resultType),
                func,
                WaitForCallbackConfig.builder().build());
    }

    public <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> typeToken, BiConsumer<String, StepContext> func) {
        return waitForCallbackAsync(
                name, typeToken, func, WaitForCallbackConfig.builder().build());
    }

    public <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            Class<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), func, waitForCallbackConfig);
    }

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
        var counter = String.valueOf(operationCounter.incrementAndGet());
        var rawId = getContextId() != null ? getContextId() + "-" + counter : counter;
        try {
            var messageDigest = MessageDigest.getInstance("SHA-256");
            var hash = messageDigest.digest(rawId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("failed to get next operation id, SHA-256 not available", e);
        }
    }
}
