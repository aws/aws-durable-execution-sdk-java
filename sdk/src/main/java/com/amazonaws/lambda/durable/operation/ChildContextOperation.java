// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.DurableConfig;
import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.ChildContextFailedException;
import com.amazonaws.lambda.durable.exception.DurableOperationException;
import com.amazonaws.lambda.durable.exception.UnrecoverableDurableExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.SuspendExecutionException;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.util.ExceptionHelper;
import com.amazonaws.services.lambda.runtime.Context;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Manages the lifecycle of a child execution context.
 *
 * <p>A child context runs a user function in a separate thread with its own operation counter and checkpoint log.
 * Operations within the child context use the child's context ID as their parentId.
 */
public class ChildContextOperation<T> extends BaseDurableOperation<T> {

    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;
    private static final String SUB_TYPE = "RUN_IN_CHILD_CONTEXT";

    private final Function<DurableContext, T> function;
    private final DurableConfig durableConfig;
    private final Context lambdaContext;
    private final ExecutionManager executionManager;
    private final ExecutorService userExecutor;
    private boolean replayChildContext;
    private T reconstructedResult;

    public ChildContextOperation(
            String operationId,
            String name,
            Function<DurableContext, T> function,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String parentId) {
        super(operationId, name, OperationType.CONTEXT, resultTypeToken, resultSerDes, executionManager, parentId);
        this.function = function;
        this.durableConfig = durableConfig;
        this.lambdaContext = lambdaContext;
        this.executionManager = executionManager;
        this.userExecutor = durableConfig.getExecutorService();
    }

    @Override
    public void execute() {
        var existing = getOperation();

        if (existing != null) {
            validateReplay(existing);
            switch (existing.status()) {
                case SUCCEEDED -> {
                    if (existing.contextDetails() != null
                            && Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                        // Large result: re-execute child context to reconstruct result
                        replayChildContext = true;
                        executeChildContext();
                    } else {
                        markAlreadyCompleted();
                    }
                }
                case FAILED -> markAlreadyCompleted();
                case STARTED -> executeChildContext();
                default ->
                    terminateExecutionWithIllegalDurableOperationException(
                            "Unexpected child context status: " + existing.status());
            }
        } else {
            // First execution: fire-and-forget START checkpoint, then run
            sendOperationUpdateAsync(
                    OperationUpdate.builder().action(OperationAction.START).subType(SUB_TYPE));
            executeChildContext();
        }
    }

    private void executeChildContext() {
        var contextId = getOperationId();

        // Register child context thread before executor runs (prevents suspension)
        registerActiveThread(contextId, ThreadType.CONTEXT);

        userExecutor.execute(() -> {
            setCurrentContext(contextId, ThreadType.CONTEXT);
            try {
                var childContext =
                        DurableContext.createChildContext(executionManager, durableConfig, lambdaContext, contextId);

                T result = function.apply(childContext);

                if (replayChildContext) {
                    // Replaying a SUCCEEDED child with replayChildren=true — skip checkpointing
                    this.reconstructedResult = result;
                    return;
                }

                checkpointSuccess(result);
            } catch (Throwable e) {
                handleChildContextFailure(e);
            } finally {
                try {
                    deregisterActiveThreadAndUnsetCurrentContext(contextId);
                } catch (SuspendExecutionException e) {
                    // Expected when this is the last active thread — suspension already signaled
                }
            }
        });
    }

    private void checkpointSuccess(T result) {
        var serialized = serializeResult(result);
        var serializedBytes = serialized.getBytes(StandardCharsets.UTF_8);

        if (serializedBytes.length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(SUB_TYPE)
                    .payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + ReplayChildren flag
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(SUB_TYPE)
                    .payload("")
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    private void handleChildContextFailure(Throwable exception) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof UnrecoverableDurableExecutionException) {
            terminateExecution((UnrecoverableDurableExecutionException) exception);
        }

        final ErrorObject errorObject;
        if (exception instanceof DurableOperationException opEx) {
            errorObject = opEx.getErrorObject();
        } else {
            errorObject = serializeException(exception);
        }

        sendOperationUpdate(OperationUpdate.builder()
                .action(OperationAction.FAIL)
                .subType(SUB_TYPE)
                .error(errorObject));
    }

    @Override
    public T get() {
        var op = waitForOperationCompletion();

        if (op.status() == OperationStatus.SUCCEEDED) {
            if (replayChildContext && reconstructedResult != null) {
                return reconstructedResult;
            }
            var contextDetails = op.contextDetails();
            var result = (contextDetails != null) ? contextDetails.result() : null;
            return deserializeResult(result);
        } else {
            var contextDetails = op.contextDetails();
            var errorObject = (contextDetails != null) ? contextDetails.error() : null;

            // Attempt to reconstruct and throw the original exception
            Throwable original = deserializeException(errorObject);
            if (original != null) {
                ExceptionHelper.sneakyThrow(original);
            }
            // Fallback: wrap in ChildContextFailedException
            throw new ChildContextFailedException(op);
        }
    }
}
