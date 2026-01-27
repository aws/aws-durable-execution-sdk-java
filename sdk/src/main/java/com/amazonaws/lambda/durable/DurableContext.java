// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.logging.DurableLogger;
import com.amazonaws.lambda.durable.logging.LoggerConfig;
import com.amazonaws.lambda.durable.operation.StepOperation;
import com.amazonaws.lambda.durable.operation.WaitOperation;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;

public class DurableContext {
    private final ExecutionManager executionManager;
    private final SerDes serDes;
    private final Context lambdaContext;
    private final AtomicInteger operationCounter;
    private final DurableLogger logger;

    DurableContext(
            ExecutionManager executionManager,
            SerDes serDes,
            Context lambdaContext,
            LoggerConfig loggerConfig,
            String contextId) {
        this.executionManager = executionManager;
        this.serDes = serDes;
        this.lambdaContext = lambdaContext;
        this.operationCounter = new AtomicInteger(0);

        var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
        this.logger = new DurableLogger(
                LoggerFactory.getLogger(DurableContext.class),
                executionManager,
                requestId,
                loggerConfig.suppressReplayLogs());

        // Register root context thread as active
        executionManager.registerActiveThreadWithContext(contextId, ThreadType.CONTEXT);
    }

    DurableContext(ExecutionManager executionManager, SerDes serDes, Context lambdaContext, LoggerConfig loggerConfig) {
        this(executionManager, serDes, lambdaContext, loggerConfig, "Root");
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        return step(
                name,
                TypeToken.get(resultType),
                func,
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, resultType, func, config).get();
    }

    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return step(
                name,
                typeToken,
                func,
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }

    public <T> T step(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        return stepAsync(name, typeToken, func, config).get();
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(
                name,
                TypeToken.get(resultType),
                func,
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        return stepAsync(name, TypeToken.get(resultType), func, config);
    }

    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func) {
        return stepAsync(
                name,
                typeToken,
                func,
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }

    public <T> DurableFuture<T> stepAsync(String name, TypeToken<T> typeToken, Supplier<T> func, StepConfig config) {
        var operationId = nextOperationId();

        // Validate replay consistency
        var existing = executionManager.getOperation(operationId);
        if (existing != null) {
            validateReplay(operationId, OperationType.STEP, name, existing);
        }

        // Create and start step operation with TypeToken
        StepOperation<T> operation =
                new StepOperation<>(operationId, name, func, typeToken, config, executionManager, logger, serDes);

        operation.execute(); // Start the step (returns immediately)

        return new DurableFuture<>(operation);
    }

    public void wait(Duration duration) {
        wait(null, duration);
    }

    public void wait(String waitName, Duration duration) {
        var operationId = nextOperationId();

        // Validate replay consistency
        var existing = executionManager.getOperation(operationId);
        if (existing != null) {
            validateReplay(operationId, OperationType.WAIT, waitName, existing);
        }

        // Create and start wait operation
        var operation = new WaitOperation(operationId, waitName, duration, executionManager);

        operation.execute(); // Checkpoint the wait
        operation.get(); // Block (will throw SuspendExecutionException if needed)
    }

    public Context getLambdaContext() {
        return lambdaContext;
    }

    public DurableLogger getLogger() {
        return logger;
    }

    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }

    /** Validates that current operation matches checkpointed operation during replay. */
    private void validateReplay(
            String operationId, OperationType expectedType, String expectedName, Operation checkpointed) {
        if (checkpointed == null || checkpointed.type() == null) {
            return; // First execution, no validation needed
        }

        if (!checkpointed.type().equals(expectedType)) {
            throw new NonDeterministicExecutionException(String.format(
                    "Operation type mismatch for \"%s\". Expected %s, got %s",
                    operationId, checkpointed.type(), expectedType));
        }

        if (!Objects.equals(checkpointed.name(), expectedName)) {
            throw new NonDeterministicExecutionException(String.format(
                    "Operation name mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                    operationId, checkpointed.name(), expectedName));
        }
    }
}
