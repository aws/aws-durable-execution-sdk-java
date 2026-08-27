// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.context.BaseContext;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.util.ExceptionHelper;

/** Adapts the asynchronous extension SPI to the blocking-compatible Java operation APIs. */
class CompletionStageDurableFuture<T> implements DurableFuture<T> {
    private final ExecutionManager executionManager;
    private final String operationType;
    private final String operationName;
    private final CompletableFuture<T> result;

    CompletionStageDurableFuture(CompletionStage<T> result) {
        this(null, null, null, result);
    }

    CompletionStageDurableFuture(ExecutionManager executionManager, CompletionStage<T> result) {
        this(executionManager, null, null, result);
    }

    CompletionStageDurableFuture(
            ExecutionManager executionManager, String operationType, String operationName, CompletionStage<T> result) {
        this.executionManager = executionManager;
        this.operationType = operationType;
        this.operationName = operationName;
        this.result = copy(Objects.requireNonNull(result, "result cannot be null"));
    }

    static <T> DurableFuture<T> from(CompletionStage<T> result) {
        return new CompletionStageDurableFuture<>(result);
    }

    static <T> DurableFuture<T> from(ExtensionContext context, CompletionStage<T> result) {
        return new CompletionStageDurableFuture<>(executionManager(context), result);
    }

    static <T> DurableFuture<T> from(
            ExtensionContext context, String operationType, String operationName, CompletionStage<T> result) {
        return new CompletionStageDurableFuture<>(
                executionManager(context),
                Objects.requireNonNull(operationType, "operationType cannot be null"),
                operationName,
                result);
    }

    static <T> DurableCallbackFuture<T> callback(
            ExtensionContext context, String callbackId, CompletionStage<T> result) {
        return new Callback<>(executionManager(context), callbackId, result);
    }

    @Override
    public T get() {
        var context = BaseContext.getCurrentContext();
        if (context instanceof BaseContextImpl contextImpl) {
            return await(contextImpl.getExecutionManager());
        }
        if (executionManager != null) {
            return await(executionManager);
        }
        try {
            return result.join();
        } catch (Throwable throwable) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(throwable));
            return null;
        }
    }

    @Override
    public CompletableFuture<Void> completionFuture() {
        return result.handle((ignored, throwable) -> {
            if (throwable != null) {
                var cause = ExceptionHelper.unwrapCompletableFuture(throwable);
                if (cause instanceof SuspendExecutionException
                        || cause instanceof UnrecoverableDurableExecutionException) {
                    ExceptionHelper.sneakyThrow(cause);
                }
            }
            return null;
        });
    }

    private T await(ExecutionManager manager) {
        var threadContext = manager.getCurrentThreadContext();
        if (threadContext != null && threadContext.threadType() == ThreadType.STEP) {
            var type = operationType == null ? "durable" : operationType;
            var target = operationName == null ? "" : " on " + operationName;
            throw new IllegalStateException(String.format(
                    "Nested %s operation is not supported%s from within a %s execution.",
                    type, target, threadContext.threadType()));
        }
        return manager.awaitFuture(result);
    }

    private static <T> CompletableFuture<T> copy(CompletionStage<T> stage) {
        var copy = new CompletableFuture<T>();
        stage.whenComplete((result, throwable) -> {
            if (throwable == null) {
                copy.complete(result);
            } else {
                copy.completeExceptionally(ExceptionHelper.unwrapCompletableFuture(throwable));
            }
        });
        return copy;
    }

    private static ExecutionManager executionManager(ExtensionContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return context instanceof BaseContextImpl contextImpl ? contextImpl.getExecutionManager() : null;
    }

    private static final class Callback<T> extends CompletionStageDurableFuture<T> implements DurableCallbackFuture<T> {
        private final String callbackId;

        private Callback(ExecutionManager executionManager, String callbackId, CompletionStage<T> result) {
            super(executionManager, null, null, result);
            this.callbackId = Objects.requireNonNull(callbackId, "callbackId cannot be null");
        }

        @Override
        public String callbackId() {
            return callbackId;
        }
    }
}
