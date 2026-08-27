// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.internal.InternalApi;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
import software.amazon.lambda.durable.primitive.BasePrimitive;
import software.amazon.lambda.durable.primitive.CallbackPrimitive;
import software.amazon.lambda.durable.primitive.ChildContextPrimitive;
import software.amazon.lambda.durable.primitive.InvokePrimitive;
import software.amazon.lambda.durable.primitive.StepPrimitive;
import software.amazon.lambda.durable.primitive.WaitPrimitive;
import software.amazon.lambda.durable.util.ParameterValidator;

/**
 * Internal bridge from extension reservations to checkpoint primitives.
 *
 * @hidden
 */
@InternalApi
public final class ExtensionOperationImpl implements ExtensionOperation {
    private final DurableContextImpl context;
    private final String operationId;
    private final String name;
    private final BasePrimitive lateCheckpointOwner;
    private final AtomicBoolean claimed = new AtomicBoolean();

    public ExtensionOperationImpl(
            DurableContextImpl context, String operationId, String name, BasePrimitive lateCheckpointOwner) {
        this.context = context;
        this.operationId = operationId;
        this.name = name;
        this.lateCheckpointOwner = lateCheckpointOwner;
    }

    @Override
    public <T> CompletionStage<T> stepAsync(
            String subType, TypeToken<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config) {
        validateSubType(subType);
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        claim();
        if (config.serDes() == null) {
            config = config.toBuilder()
                    .serDes(context.getDurableConfig().getSerDes())
                    .build();
        }
        var operation = new StepPrimitive<>(
                new PrimitiveOperationIdentifier(operationId, name, OperationType.STEP, subType),
                function,
                resultType,
                config,
                context);
        operation.execute();
        return resultStage(operation, operation);
    }

    @Override
    public CompletionStage<Void> waitAsync(String subType, Duration duration) {
        validateSubType(subType);
        ParameterValidator.validateDuration(duration, "Wait duration");
        claim();
        var operation = new WaitPrimitive(
                new PrimitiveOperationIdentifier(operationId, name, OperationType.WAIT, subType), duration, context);
        operation.execute();
        return resultStage(operation, operation);
    }

    @Override
    public <T, U> CompletionStage<T> invokeAsync(
            String subType, String functionName, U payload, TypeToken<T> resultType, ExtensionInvokeConfig config) {
        validateSubType(subType);
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        claim();
        if (config.serDes() == null) {
            config = config.toBuilder()
                    .serDes(context.getDurableConfig().getSerDes())
                    .build();
        }
        if (config.payloadSerDes() == null) {
            config = config.toBuilder()
                    .payloadSerDes(context.getDurableConfig().getSerDes())
                    .build();
        }
        var operation = new InvokePrimitive<>(
                new PrimitiveOperationIdentifier(operationId, name, OperationType.CHAINED_INVOKE, subType),
                functionName,
                payload,
                resultType,
                config,
                context);
        operation.execute();
        return resultStage(operation, operation);
    }

    @Override
    public <T> ExtensionCallback<T> createCallback(
            String subType, TypeToken<T> resultType, ExtensionCallbackConfig config) {
        validateSubType(subType);
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        claim();
        if (config.serDes() == null) {
            config = config.toBuilder()
                    .serDes(context.getDurableConfig().getSerDes())
                    .build();
        }
        var operation = new CallbackPrimitive<>(
                new PrimitiveOperationIdentifier(operationId, name, OperationType.CALLBACK, subType),
                resultType,
                config,
                context);
        operation.execute();
        var result = resultStage(operation, operation);
        return new ExtensionCallback<>(operation.callbackId(), result);
    }

    @Override
    public <T> CompletionStage<T> runInChildContextAsync(
            String subType,
            TypeToken<T> resultType,
            ExtensionContextFunction<T> function,
            ExtensionContextConfig config) {
        validateSubType(subType);
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        claim();
        if (config.serDes() == null) {
            config = config.toBuilder()
                    .serDes(context.getDurableConfig().getSerDes())
                    .build();
        }
        var operation = new ChildContextPrimitive<>(
                new PrimitiveOperationIdentifier(operationId, name, OperationType.CONTEXT, subType),
                function,
                resultType,
                config,
                context,
                lateCheckpointOwner);
        operation.execute();
        return resultStage(operation, operation);
    }

    private static <T> CompletionStage<T> resultStage(DurableFuture<T> future, BasePrimitive operation) {
        return operation
                .getCompletionFuture()
                .thenApply(ignored -> future.get())
                .minimalCompletionStage();
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
