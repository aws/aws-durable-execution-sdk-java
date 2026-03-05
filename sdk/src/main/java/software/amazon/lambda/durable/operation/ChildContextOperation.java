// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.execution.ExecutionManager.isTerminalStatus;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackSubmitterException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.OperationSubType;
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
    private final ExecutorService userExecutor;
    private boolean replayChildContext;
    private T reconstructedResult;
    private final OperationSubType subType;

    public ChildContextOperation(
            String operationId,
            String name,
            Function<DurableContext, T> function,
            OperationSubType subType,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContext durableContext) {
        super(operationId, name, OperationType.CONTEXT, resultTypeToken, resultSerDes, durableContext);
        this.function = function;
        this.userExecutor = getContext().getDurableConfig().getExecutorService();
        this.subType = subType;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        // First execution: fire-and-forget START checkpoint, then run
        sendOperationUpdateAsync(
                OperationUpdate.builder().action(OperationAction.START).subType(subType.getValue()));
        executeChildContext();
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
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
    }

    private void executeChildContext() {
        // The operationId is already globally unique (prefixed by parent context path via
        // DurableContext.nextOperationId), so we use it directly as the contextId.
        // E.g., first level child context "hash(1)",
        //       second level child context "hash(hash(1)-2)",
        //       third level child context "hash(hash(hash(1)-2)-1)".
        var contextId = getOperationId();

        // Thread registration is intentionally split across two threads:
        // 1. registerActiveThread on the PARENT thread — ensures the child is tracked before the
        //    parent can deregister and trigger suspension (race prevention).
        // 2. setCurrentContext on the CHILD thread — sets the ThreadLocal so operations inside
        //    the child context know which context they belong to.
        // registerActiveThread is idempotent (no-op if already registered).
        registerActiveThread(contextId);

        Runnable userHandler = () -> {
            // use a try-with-resources to
            // - add thread id/type to thread local when the step starts
            // - clear logger properties when the step finishes
            try (var childContext = getContext().createChildContext(contextId, getName())) {
                try {
                    T result = function.apply(childContext);

                    handleChildContextSuccess(result);
                } catch (Throwable e) {
                    handleChildContextFailure(e);
                }
            }
        };

        // Execute user provided child context code in user-configured executor
        CompletableFuture.runAsync(userHandler, userExecutor);
    }

    private void handleChildContextSuccess(T result) {
        if (replayChildContext) {
            // Replaying a SUCCEEDED child with replayChildren=true — skip checkpointing.
            // Mark the completableFuture completed so get() doesn't block waiting for a checkpoint response.
            this.reconstructedResult = result;
            markAlreadyCompleted();
        } else {
            checkpointSuccess(result);
        }
    }

    private void checkpointSuccess(T result) {
        var serialized = serializeResult(result);
        var serializedBytes = serialized.getBytes(StandardCharsets.UTF_8);

        if (serializedBytes.length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(subType.getValue())
                    .payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + ReplayChildren flag.
            // Store the result so get() can return it directly without deserializing the empty payload.
            this.reconstructedResult = result;
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(subType.getValue())
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
                .subType(subType.getValue())
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

            // throw a general failed exception if a user exception is not reconstructed
            return switch (subType) {
                case WAIT_FOR_CALLBACK -> handleWaitForCallbackFailure(op);
                // todo: handle MAP/PARALLEL
                case MAP -> throw new ChildContextFailedException(op);
                case PARALLEL -> throw new ChildContextFailedException(op);
                case RUN_IN_CHILD_CONTEXT -> throw new ChildContextFailedException(op);
            };
        }
    }

    private T handleWaitForCallbackFailure(Operation op) {
        var childrenOps = getChildOperations(op.id());
        var callbackOp = childrenOps.stream()
                .filter(o -> o.type() == OperationType.CALLBACK)
                .findFirst()
                .orElse(null);
        var submitterOp = childrenOps.stream()
                .filter(o -> o.type() == OperationType.STEP)
                .findFirst()
                .orElse(null);
        if (callbackOp != null) {
            // if callback failed
            if (isTerminalStatus(callbackOp.status())) {
                switch (callbackOp.status()) {
                    case FAILED -> throw new CallbackFailedException(callbackOp);
                    case TIMED_OUT -> throw new CallbackTimeoutException(callbackOp);
                }
            }

            // if submitter failed
            if (submitterOp != null
                    && isTerminalStatus(submitterOp.status())
                    && submitterOp.status() != OperationStatus.SUCCEEDED) {
                var stepError = submitterOp.stepDetails().error();
                if (StepInterruptedException.isStepInterruptedException(stepError)) {
                    throw new CallbackSubmitterException(callbackOp, new StepInterruptedException(submitterOp));
                } else {
                    throw new CallbackSubmitterException(callbackOp, new StepFailedException(submitterOp));
                }
            }
        }

        throw new IllegalStateException("Unknown waitForCallback status");
    }
}
