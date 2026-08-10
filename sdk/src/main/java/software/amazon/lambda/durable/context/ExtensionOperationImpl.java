// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ExtensionOperation;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;

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
    public DurableFuture<Void> waitAsync(Duration duration) {
        claim();
        return context.waitAsyncWithId(operationId, name, duration);
    }

    @Override
    public <T, U> DurableFuture<T> invokeAsync(
            String functionName, U payload, TypeToken<T> resultType, InvokeConfig config) {
        claim();
        return context.invokeAsyncWithId(operationId, name, functionName, payload, resultType, config);
    }

    @Override
    public <T> DurableCallbackFuture<T> createCallback(TypeToken<T> resultType, CallbackConfig config) {
        claim();
        return context.createCallbackWithId(operationId, name, resultType, config);
    }

    @Override
    public <T> DurableFuture<T> runInChildContextAsync(
            TypeToken<T> resultType, Supplier<T> function, RunInChildContextConfig config) {
        claim();
        return context.runInChildContextAsyncWithId(
                operationId, name, resultType, ignored -> function.get(), config);
    }

    private void claim() {
        if (!claimed.compareAndSet(false, true)) {
            throw new IllegalStateException("An extension operation reservation can only be used once");
        }
    }
}
