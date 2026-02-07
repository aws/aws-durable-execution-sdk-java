// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.logging.DurableLogger;
import com.amazonaws.lambda.durable.operation.CallbackOperation;
import com.amazonaws.lambda.durable.operation.InvokeOperation;
import com.amazonaws.lambda.durable.operation.StepOperation;
import com.amazonaws.lambda.durable.operation.WaitOperation;
import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

public class DurableContext {
    private static final String ROOT_CONTEXT = "Root";

    private final ExecutionManager executionManager;
    private final DurableConfig durableConfig;
    private final Context lambdaContext;
    private final AtomicInteger operationCounter;
    private final DurableLogger logger;
    private final ExecutionContext executionContext;

    DurableContext(
            ExecutionManager executionManager, DurableConfig durableConfig, Context lambdaContext, String contextId) {
        this.executionManager = executionManager;
        this.durableConfig = durableConfig;
        this.lambdaContext = lambdaContext;
        this.operationCounter = new AtomicInteger(0);
        this.executionContext = new ExecutionContext(executionManager.getDurableExecutionArn());

        var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
        this.logger = new DurableLogger(
                LoggerFactory.getLogger(DurableContext.class),
                executionManager,
                requestId,
                durableConfig.getLoggerConfig().suppressReplayLogs());

        // Register root context thread as active
        executionManager.registerActiveThread(contextId, ThreadType.CONTEXT);
        executionManager.setCurrentContext(contextId, ThreadType.CONTEXT);
    }

    DurableContext(ExecutionManager executionManager, DurableConfig config, Context lambdaContext) {
        this(executionManager, config, lambdaContext, ROOT_CONTEXT);
    }

    // ========== step methods ==========

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
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(typeToken, "typeToken cannot be null");
        if (config.serDes() == null) {
            config = config.toBuilder().serDes(durableConfig.getSerDes()).build();
        }
        var operationId = nextOperationId();

        // Create and start step operation with TypeToken
        var operation = new StepOperation<>(
                operationId, name, func, typeToken, config, executionManager, logger, durableConfig);

        operation.execute(); // Start the step (returns immediately)

        return operation;
    }

    // ========== wait methods ==========

    public Void wait(Duration duration) {
        return wait(null, duration);
    }

    public Void wait(String waitName, Duration duration) {
        var operationId = nextOperationId();

        // Create and start wait operation
        var operation = new WaitOperation(operationId, waitName, duration, executionManager);

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
            config = config.toBuilder().serDes(durableConfig.getSerDes()).build();
        }
        if (config.payloadSerDes() == null) {
            config = config.toBuilder().payloadSerDes(durableConfig.getSerDes()).build();
        }
        var operationId = nextOperationId();

        // Create and start invoke operation
        var operation =
                new InvokeOperation<>(operationId, name, functionName, payload, typeToken, config, executionManager);

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
            config = config.toBuilder().serDes(durableConfig.getSerDes()).build();
        }
        var operationId = nextOperationId();

        var operation = new CallbackOperation<>(operationId, name, typeToken, config, executionManager);
        operation.execute();

        return operation;
    }

    // =============== accessors ================

    public Context getLambdaContext() {
        return lambdaContext;
    }

    public DurableLogger getLogger() {
        return logger;
    }

    /**
     * Returns metadata about the current durable execution.
     *
     * <p>The execution context provides information that remains constant throughout the execution lifecycle, such as
     * the durable execution ARN. This is useful for tracking execution progress, correlating logs, and referencing this
     * execution in external systems.
     *
     * @return the execution context
     */
    public ExecutionContext getExecutionContext() {
        return executionContext;
    }

    // ============= internal utilities ===============

    /** Get the next operationId (latest operationId + 1) */
    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }
}
