// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.operation.CallbackOperation;
import software.amazon.lambda.durable.operation.ChildContextOperation;
import software.amazon.lambda.durable.operation.InvokeOperation;
import software.amazon.lambda.durable.operation.StepOperation;
import software.amazon.lambda.durable.operation.WaitOperation;
import software.amazon.lambda.durable.validation.ParameterValidator;

public class DurableContext extends BaseContext {
    private final AtomicInteger operationCounter;
    private final DurableLogger logger;

    /** Shared initialization — sets all fields. */
    private DurableContext(
            ExecutionManager executionManager, DurableConfig durableConfig, Context lambdaContext, String contextId) {
        super(executionManager, durableConfig, lambdaContext, contextId);
        this.operationCounter = new AtomicInteger(0);

        var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
        this.logger = new DurableLogger(
                LoggerFactory.getLogger(DurableContext.class),
                executionManager,
                requestId,
                durableConfig.getLoggerConfig().suppressReplayLogs());
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
        return new DurableContext(executionManager, durableConfig, lambdaContext, null);
    }

    /**
     * Creates a child context.
     *
     * @param childContextId the child context's ID (the CONTEXT operation's operation ID)
     * @return a new DurableContext for the child context
     */
    public DurableContext createChildContext(String childContextId) {
        return new DurableContext(executionManager, getDurableConfig(), getLambdaContext(), childContextId);
    }

    /**
     * Creates a step context for executing step operations.
     *
     * @param stepOperationId the ID of the step operation (used for thread registration)
     * @return a new StepContext instance
     */
    public StepContext createStepContext(String stepOperationId) {
        return new StepContext(executionManager, getDurableConfig(), getLambdaContext(), stepOperationId);
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

    public Void wait(Duration duration) {
        return wait(null, duration);
    }

    public Void wait(String waitName, Duration duration) {
        ParameterValidator.validateDuration(duration, "Wait duration");
        var operationId = nextOperationId();

        // Create and start wait operation
        var operation = new WaitOperation(operationId, waitName, duration, this);

        operation.execute(); // Checkpoint the wait
        return operation.get(); // Block (will throw SuspendExecutionException if needed)
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
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        var operationId = nextOperationId();

        var operation = new ChildContextOperation<>(
                operationId, name, func, typeToken, getDurableConfig().getSerDes(), this);

        operation.execute();
        return operation;
    }

    // =============== accessors ================
    /**
     * Returns a logger with execution context information for replay-aware logging.
     *
     * @return the durable logger
     */
    public DurableLogger getLogger() {
        return logger;
    }

    /**
     * Get the next operationId. For root contexts, returns sequential IDs like "1", "2", "3". For child contexts,
     * prefixes with the contextId to ensure global uniqueness, e.g. "1-1", "1-2" for operations inside child context
     * "1". This matches the JavaScript SDK's stepPrefix convention and prevents ID collisions in checkpoint batches.
     */
    private String nextOperationId() {
        var counter = String.valueOf(operationCounter.incrementAndGet());
        return getContextId() != null ? getContextId() + "-" + counter : counter;
    }
}
