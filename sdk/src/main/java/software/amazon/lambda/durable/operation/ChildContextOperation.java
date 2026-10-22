// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.execution.ExecutionManager.isTerminalStatus;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
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
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.exception.MapIterationFailedException;
import software.amazon.lambda.durable.exception.ParallelBranchFailedException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.DeserializedOperationResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
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
    private final AtomicReference<DeserializedOperationResult<T>> cachedOperationResult = new AtomicReference<>(null);

    // child context for RunInChildContext
    public ChildContextOperation(
            OperationIdentifier operationIdentifier,
            Function<DurableContext, T> function,
            TypeToken<T> resultTypeToken,
            RunInChildContextConfig config,
            DurableContextImpl durableContext) {
        this(operationIdentifier, function, resultTypeToken, config, durableContext, null);
    }

    // child context for a ConcurrencyOperation branch
    public ChildContextOperation(
            OperationIdentifier operationIdentifier,
            Function<DurableContext, T> function,
            TypeToken<T> resultTypeToken,
            RunInChildContextConfig config,
            DurableContextImpl durableContext,
            ConcurrencyOperation<?> parentOperation) {
        super(
                operationIdentifier,
                resultTypeToken,
                config.serDes(),
                durableContext,
                parentOperation,
                config.isVirtual());
        this.function = function;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        // First execution: fire-and-forget START checkpoint, then run
        if (!isVirtual) {
            sendOperationUpdateAsync(OperationUpdate.builder().action(OperationAction.START));
        }
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
            var childContext = getContext().createChildContext(contextId, getName(), isVirtual);
            DurableContextImpl.setCurrentContext(childContext);
            try (var ignored = DurableLogger.attachContext()) {
                try {
                    // Run the user function inside the plugin hook boundary (attempt is null for contexts)
                    // so a failure is reported through onUserFunctionEnd; checkpointing stays outside.
                    T result = runUserFunction(null, () -> function.apply(childContext));

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
        var serializedResult = serializeAndDeserializeResult(result);

        if (replayChildren.get() || isVirtual || parentOperation != null && parentOperation.isOperationCompleted()) {
            // Skip checkpointing if
            // - parent ConcurrencyOperation has already completed, preventing race conditions where a child finishes
            // after the parent has already completed.
            // - replaying a SUCCEEDED child with replayChildren=true — skip checkpointing.
            // - nestingType is FLAT
            // Mark the completableFuture completed so get() doesn't block waiting for a checkpoint response.
            cachedOperationResult.set(DeserializedOperationResult.succeeded(serializedResult.deserialized()));
            if (isVirtual) {
                fireOnOperationEnd(null, null, false);
            }
            markAlreadyCompleted();
        } else {
            checkpointSuccess(serializedResult.deserialized(), serializedResult.serialized());
        }
    }

    private void checkpointSuccess(T result, String serialized) {
        if (serialized == null || serialized.getBytes(StandardCharsets.UTF_8).length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(
                    OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + ReplayChildren flag.
            // Store the result so get() can return it directly without deserializing the empty payload.
            cachedOperationResult.set(DeserializedOperationResult.succeeded(result));
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .payload("")
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    private void handleChildContextFailure(Throwable exception) {
        executionManager.rethrowPluginFatalIfPresent();
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException suspendExecutionException) {
            // Rethrow Error immediately — do not checkpoint
            throw suspendExecutionException;
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
            // terminate the execution and throw the exception if it's not recoverable
            throw terminateExecution(unrecoverableDurableExecutionException);
        }

        final ErrorObject errorObject;
        if (exception instanceof DurableOperationException opEx) {
            // waitForCallback rethrows the callback's own exception, whose error object is the callback's; its context
            // keeps that same object so a replay that can only read the context reports the same error object.
            errorObject =
                    getSubType() == OperationSubType.WAIT_FOR_CALLBACK ? opEx.getErrorObject() : wrappedError(opEx);
        } else {
            errorObject = serializeException(exception);
        }

        var op = createVirtualOperation(errorObject);
        cachedOperationResult.set(DeserializedOperationResult.failed(translateException(op, errorObject)));

        // Skip checkpointing if
        // - parent ConcurrencyOperation has already completed, preventing race conditions where a child finishes after
        // the parent has already succeeded.
        // - this child is not a direct child of a parent context (i.e. nestingType == FLAT), such as a parallel branch.
        if ((parentOperation != null && parentOperation.isOperationCompleted()) || isVirtual) {
            if (isVirtual) {
                fireOnOperationEnd(null, exception, false);
            }
            markAlreadyCompleted();
            return;
        }

        sendOperationUpdate(
                OperationUpdate.builder().action(OperationAction.FAIL).error(errorObject));
    }

    @Override
    public T get() {
        var op = waitForOperationCompletion();
        if (cachedOperationResult.get() != null) {
            // we have a result, just return it directly
            return cachedOperationResult.get().get();
        }

        if (op.status() == OperationStatus.SUCCEEDED) {
            var contextDetails = op.contextDetails();
            var result = (contextDetails != null) ? contextDetails.result() : null;
            return deserializeResult(result);
        } else {
            var contextDetails = op.contextDetails();
            var errorObject = (contextDetails != null) ? contextDetails.error() : null;

            ExceptionHelper.sneakyThrow(translateException(op, errorObject));
            return null;
        }
    }

    /**
     * Returns the error to checkpoint for a context that failed because an SDK operation exception escaped it: the
     * wrapped (original) error, never the wrapper.
     *
     * <p>A wrapper such as {@code StepFailedException} or {@code CallbackFailedException} carries the original error's
     * {@link ErrorObject}, which is checkpointed as it is. An SDK error that names the failure itself (a timed-out
     * callback, a timed-out or stopped invoke), and a callback or invoke failure whose error has no type, is
     * checkpointed under its own class name instead, keeping the original message, data and stack trace when there are
     * any: its {@link ErrorObject} is missing or is the stop error, so it does not say which error failed the context.
     * The context checkpoint then says which error failed it without its children, which the backend can prune once the
     * context completes. Any other operation exception keeps its error object as it is, including a missing one.
     */
    static ErrorObject wrappedError(DurableOperationException exception) {
        var original = exception.getErrorObject();
        boolean namedBySdk = exception instanceof CallbackTimeoutException
                || exception instanceof InvokeTimedOutException
                || exception instanceof InvokeStoppedException;
        boolean typelessFailure = (exception.getClass() == CallbackFailedException.class
                        || exception.getClass() == InvokeFailedException.class)
                && (original == null || isBlankType(original.errorType()));
        if (!namedBySdk && !typelessFailure) {
            return original;
        }
        var message =
                original != null && original.errorMessage() != null ? original.errorMessage() : exception.getMessage();
        var stackTrace = original != null
                        && original.stackTrace() != null
                        && !original.stackTrace().isEmpty()
                ? original.stackTrace()
                : ExceptionHelper.serializeStackTrace(exception.getStackTrace());
        return ErrorObject.builder()
                .errorType(exception.getClass().getName())
                .errorMessage(message)
                .errorData(original != null ? original.errorData() : null)
                .stackTrace(stackTrace)
                .build();
    }

    /**
     * {@code true} for a missing type or one made only of ASCII whitespace (space, tab, line feed, carriage return,
     * form feed, vertical tab).
     */
    private static boolean isBlankType(String type) {
        return type == null || type.chars().allMatch(c -> c == ' ' || (c >= '\t' && c <= '\r'));
    }

    /** {@code true} when {@code errorType} names an SDK operation exception, which is never rebuilt from error data. */
    private static boolean isSdkOperationException(String errorType) {
        if (errorType == null || !errorType.startsWith(DurableOperationException.class.getPackageName() + ".")) {
            return false;
        }
        try {
            return DurableOperationException.class.isAssignableFrom(Class.forName(errorType));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Throwable translateException(Operation op, ErrorObject errorObject) {
        // Attempt to reconstruct and throw the original exception. An SDK operation exception recorded by
        // wrappedError is not rebuilt from its data: the context reports it through its own failure type below.
        Throwable original = errorObject != null && isSdkOperationException(errorObject.errorType())
                ? null
                : deserializeException(errorObject);
        if (original != null) {
            return original;
        }

        // throw a general failed exception if a user exception is not reconstructed
        return switch (getSubType()) {
            case WAIT_FOR_CALLBACK -> handleWaitForCallbackFailure(op);
            case MAP_ITERATION -> new MapIterationFailedException(op);
            case PARALLEL_BRANCH -> new ParallelBranchFailedException(op);
            case RUN_IN_CHILD_CONTEXT, WITH_RETRY -> new ChildContextFailedException(op);

            // the following subtypes should not be able to reach here
            case PARALLEL, MAP, WAIT_FOR_CONDITION, STEP, WAIT, CALLBACK, CHAINED_INVOKE ->
                new IllegalStateException("Unexpected sub-type: " + getSubType());
        };
    }

    private Operation createVirtualOperation(ErrorObject errorObject) {
        return Operation.builder()
                .id(getOperationId())
                .name(getName())
                .type(OperationType.CONTEXT)
                .status(OperationStatus.FAILED)
                .contextDetails(ContextDetails.builder().error(errorObject).build())
                .build();
    }

    private Throwable handleWaitForCallbackFailure(Operation failedContext) {
        var childrenOps = getChildOperations();
        var callbackOp = childrenOps.stream()
                .filter(o -> o.type() == OperationType.CALLBACK)
                .findFirst()
                .orElse(null);
        var submitterOp = childrenOps.stream()
                .filter(o -> o.type() == OperationType.STEP)
                .findFirst()
                .orElse(null);
        var knownFailure = knownWaitForCallbackFailure(callbackOp, submitterOp);
        if (knownFailure != null) return knownFailure;

        // Completed child contexts may replay without their nested operations.
        // Report the stored context failure without guessing its callback/submitter
        // category or fabricating the child operation that is no longer available.
        if (callbackOp == null
                || callbackOp.callbackDetails() == null
                || submitterOp == null
                || submitterOp.stepDetails() == null) {
            return new ChildContextFailedException(failedContext);
        }

        return new IllegalStateException("Unknown waitForCallback status");
    }

    private Throwable knownWaitForCallbackFailure(Operation callbackOp, Operation submitterOp) {
        if (callbackOp == null || callbackOp.callbackDetails() == null) return null;
        if (callbackOp.status() == OperationStatus.FAILED) return new CallbackFailedException(callbackOp);
        if (callbackOp.status() == OperationStatus.TIMED_OUT) return new CallbackTimeoutException(callbackOp);
        if (submitterOp == null
                || submitterOp.stepDetails() == null
                || !isTerminalStatus(submitterOp.status())
                || submitterOp.status() == OperationStatus.SUCCEEDED) return null;

        var stepError = submitterOp.stepDetails().error();
        var stepFailure = StepInterruptedException.isStepInterruptedException(stepError)
                ? new StepInterruptedException(submitterOp)
                : new StepFailedException(submitterOp);
        return new CallbackSubmitterException(callbackOp, stepFailure);
    }
}
