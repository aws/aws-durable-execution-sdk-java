// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.RUN_IN_CHILD_CONTEXT;

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
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Manages the lifecycle of a child execution context.
 *
 * <p>A child context runs a user function in a separate thread with its own operation counter and checkpoint log.
 * Operations within the child context use the child's context ID as their parentId.
 */
public class ChildContextOperation<T> extends BaseDurableOperation<T> {

    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

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
                    OperationUpdate.builder().action(OperationAction.START).subType(RUN_IN_CHILD_CONTEXT.getValue()));
            executeChildContext();
        }
    }

    private void executeChildContext() {
        // The operationId is already globally unique (prefixed by parent context path via
        // DurableContext.nextOperationId), so we use it directly as the contextId.
        // E.g., root child context "1", nested child context "1-2", deeply nested "1-2-1".
        var contextId = getOperationId();

        // Thread registration is intentionally split across two threads:
        // 1. registerActiveThread on the PARENT thread — ensures the child is tracked before the
        //    parent can deregister and trigger suspension (race prevention).
        // 2. setCurrentContext on the CHILD thread — sets the ThreadLocal so operations inside
        //    the child context know which context they belong to.
        // registerActiveThread is idempotent (no-op if already registered).
        registerActiveThread(contextId);

        userExecutor.execute(() -> {
            setCurrentThreadContext(new ThreadContext(contextId, ThreadType.CONTEXT));
            try {
                var childContext =
                        DurableContext.createChildContext(executionManager, durableConfig, lambdaContext, contextId);

                T result = function.apply(childContext);

                if (replayChildContext) {
                    // Replaying a SUCCEEDED child with replayChildren=true — skip checkpointing.
                    // Advance the phaser so get() doesn't block waiting for a checkpoint response.
                    this.reconstructedResult = result;
                    markAlreadyCompleted();
                    return;
                }

                checkpointSuccess(result);
            } catch (Throwable e) {
                handleChildContextFailure(e);
            } finally {
                try {
                    deregisterActiveThread(contextId);
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
                    .subType(RUN_IN_CHILD_CONTEXT.getValue())
                    .payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + ReplayChildren flag.
            // Store the result so get() can return it directly without deserializing the empty payload.
            this.reconstructedResult = result;
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(RUN_IN_CHILD_CONTEXT.getValue())
                    .payload("")
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    private void handleChildContextFailure(Throwable exception) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException) {
            // Rethrow Error immediately — do not checkpoint
            ExceptionHelper.sneakyThrow(exception);
        }
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
                .subType(RUN_IN_CHILD_CONTEXT.getValue())
                .error(errorObject));
    }

    @Override
    public T get() {
        var op = waitForOperationCompletion();

        if (op.status() == OperationStatus.SUCCEEDED) {
            if (reconstructedResult != null) {
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
