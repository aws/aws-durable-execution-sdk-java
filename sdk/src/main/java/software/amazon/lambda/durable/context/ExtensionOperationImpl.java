// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;

final class ExtensionOperationImpl implements ExtensionOperation {
    private final DurableContextImpl context;
    private final String operationId;
    private final String name;
    private final AtomicBoolean claimed = new AtomicBoolean();

    ExtensionOperationImpl(DurableContextImpl context, String operationId, String name) {
        this.context = context;
        this.operationId = operationId;
        this.name = name;
    }

    @Override
    public <T> DurableFuture<T> stepAsync(TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        claim();
        return context.stepAsyncWithId(operationId, name, resultType, ignored -> function.get(), config);
    }

    @Override
    public <T> DurableFuture<T> stepAsync(
            String subType, TypeToken<T> resultType, Supplier<T> function, StepConfig config) {
        validateSubType(subType);
        claim();
        return context.stepAsyncWithId(operationId, name, subType, resultType, ignored -> function.get(), config);
    }

    @Override
    public <T> DurableFuture<T> stepAsync(
            String subType, TypeToken<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config) {
        validateSubType(subType);
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        claim();
        return context.extensionStepAsyncWithId(operationId, name, subType, resultType, function, config);
    }

    @Override
    public DurableFuture<Void> waitAsync(Duration duration) {
        claim();
        return context.waitAsyncWithId(operationId, name, duration);
    }

    @Override
    public DurableFuture<Void> waitAsync(String subType, Duration duration) {
        validateSubType(subType);
        claim();
        return context.waitAsyncWithId(operationId, name, subType, duration);
    }

    @Override
    public <T, U> DurableFuture<T> invokeAsync(
            String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        claim();
        return context.invokeAsyncWithId(operationId, name, functionName, payload, resultType, config);
    }

    @Override
    public <T, U> DurableFuture<T> invokeAsync(
            String subType, String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        validateSubType(subType);
        claim();
        return context.invokeAsyncWithId(operationId, name, subType, functionName, payload, resultType, config);
    }

    @Override
    public <T> DurableCallbackFuture<T> createCallback(TypeToken<T> resultType, CallbackConfig config) {
        claim();
        return context.createCallbackWithId(operationId, name, resultType, config);
    }

    @Override
    public <T> DurableCallbackFuture<T> createCallback(String subType, TypeToken<T> resultType, CallbackConfig config) {
        validateSubType(subType);
        claim();
        return context.createCallbackWithId(operationId, name, subType, resultType, config);
    }

    @Override
    public <T> DurableFuture<T> runInChildContextAsync(
            TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        claim();
        return context.runInChildContextAsyncWithId(operationId, name, resultType, ignored -> function.get(), config);
    }

    @Override
    public <T> DurableFuture<T> runInChildContextAsync(
            String subType, TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        validateSubType(subType);
        claim();
        return context.runInChildContextAsyncWithId(
                operationId, name, subType, resultType, ignored -> function.get(), config);
    }

    @Override
    public <T> DurableFuture<T> runInChildContextAsync(
            String subType,
            TypeToken<T> resultType,
            ExtensionContextFunction<T> function,
            ExtensionContextConfig config) {
        validateSubType(subType);
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        claim();
        return context.extensionContextAsyncWithId(operationId, name, subType, resultType, function, config);
    }

    private void validateSubType(String subType) {
        Objects.requireNonNull(subType, "subType cannot be null");
        if (subType.isBlank()) {
            throw new IllegalArgumentException("subType cannot be blank");
        }
    }

    private void claim() {
        if (!claimed.compareAndSet(false, true)) {
            throw new IllegalStateException("An extension operation reservation can only be used once");
        }
    }
}
