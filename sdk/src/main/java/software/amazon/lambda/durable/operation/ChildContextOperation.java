// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.execution.ExecutionManager.isTerminalStatus;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
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
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackSubmitterException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.MapIterationFailedException;
import software.amazon.lambda.durable.exception.ParallelBranchFailedException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Manages the lifecycle of a child execution context.
 *
 * <p>A child context runs a user function in a separate thread with its own operation counter and checkpoint log.
 * Operations within the child context use the child's context ID as their parentId.
 *
 * <p>When created as part of a {@link ConcurrencyOperation} (e.g., parallel execution), the child notifies its parent
 * on completion via {@code onItemComplete()} BEFORE closing its own child context. It also skips checkpointing if the
 * parent operation has already succeeded.
 */
public class ChildContextOperation<T> extends SerializableDurableOperation<T> {

    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    private final Function<DurableContext, T> function;
    private final AtomicBoolean replayChildren = new AtomicBoolean(false);
    private T reconstructedResult;

    public ChildContextOperation(
            OperationIdentifier operationIdentifier,
            Function<DurableContext, T> function,
            TypeToken<T> resultTypeToken,
            RunInChildContextConfig config,
            DurableContextImpl durableContext) {
        this(operationIdentifier, function, resultTypeToken, config, durableContext, null);
    }

    public ChildContextOperation(
            OperationIdentifier operationIdentifier,
            Function<DurableContext, T> function,
            TypeToken<T> resultTypeToken,
            RunInChildContextConfig config,
            DurableContextImpl durableContext,
            ConcurrencyOperation<?> parentOperation) {
        super(operationIdentifier, resultTypeToken, config.serDes(), durableContext, parentOperation);
        this.function = function;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        // First execution: fire-and-forget START checkpoint, then run
        sendOperationUpdateAsync(OperationUpdate.builder().action(OperationAction.START));
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
                    replayChildren.set(true);
                    executeChildContext();
                } else {
                    markAlreadyCompleted();
                }
            }
            case FAILED -> markAlreadyCompleted();
            case STARTED -> executeChildContext();
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
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

        Runnable userHandler = () -> {
            // use a try-with-resources to
            // - add thread id/type to thread local when the step starts
            // - clear logger properties when the step finishes
            //
            // When this child is part of a ConcurrencyOperation (parentOperation != null),
            // we notify the parent BEFORE closing the child context. This ensures the parent
            // can trigger the next queued branch while the current child context is still valid.
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
        runUserHandler(userHandler, ThreadType.CONTEXT);
    }

    private void handleChildContextSuccess(T result) {
        if (replayChildren.get()) {
            // Replaying a SUCCEEDED child with replayChildren=true — skip checkpointing.
            // Mark the completableFuture completed so get() doesn't block waiting for a checkpoint response.
            this.reconstructedResult = result;
            markAlreadyCompleted();
        } else {
            checkpointSuccess(result);
        }
    }

    private void checkpointSuccess(T result) {
        // Skip checkpointing if parent ConcurrencyOperation has already completed —
        // prevents race conditions where a child finishes after the parent has already completed.
        if (parentOperation != null && parentOperation.isOperationCompleted()) {
            return;
        }

        var serialized = serializeResult(result);

        if (serialized == null || serialized.getBytes(StandardCharsets.UTF_8).length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(
                    OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + ReplayChildren flag.
            // Store the result so get() can return it directly without deserializing the empty payload.
            this.reconstructedResult = result;
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .payload("")
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    private void handleChildContextFailure(Throwable exception) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException suspendExecutionException) {
            // Rethrow Error immediately — do not checkpoint
            throw suspendExecutionException;
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
            // terminate the execution and throw the exception if it's not recoverable
            throw terminateExecution(unrecoverableDurableExecutionException);
        }

        // Skip checkpointing if parent ConcurrencyOperation has already completed —
        // prevents race conditions where a child finishes after the parent has already succeeded.
        if (parentOperation != null && parentOperation.isOperationCompleted()) {
            return;
        }

        final ErrorObject errorObject;
        if (exception instanceof DurableOperationException opEx) {
            errorObject = opEx.getErrorObject();
        } else {
            errorObject = serializeException(exception);
        }

        sendOperationUpdate(
                OperationUpdate.builder().action(OperationAction.FAIL).error(errorObject));
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
            return switch (getSubType()) {
                case WAIT_FOR_CALLBACK -> handleWaitForCallbackFailure();
                case MAP_ITERATION -> throw new MapIterationFailedException(op);
                case PARALLEL_BRANCH -> throw new ParallelBranchFailedException(op);
                case RUN_IN_CHILD_CONTEXT -> throw new ChildContextFailedException(op);

                // the following subtypes should not be able to reach here
                case PARALLEL, MAP, WAIT_FOR_CONDITION ->
                    throw new IllegalStateException("Unexpected sub-type: " + getSubType());
            };
        }
    }

    private T handleWaitForCallbackFailure() {
        var childrenOps = getChildOperations();
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
