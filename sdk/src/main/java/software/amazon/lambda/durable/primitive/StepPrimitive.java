// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepOptions;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Durable operation that executes a user-provided function with retry support.
 *
 * <p>Steps are the primary unit of work in a durable execution. Each step is checkpointed before and after execution,
 * enabling automatic retry on failure and replay on re-invocation.
 *
 * @param <T> the result type of the step function
 */
public class StepPrimitive<T> extends SerializablePrimitive<T> {
    private static final Integer FIRST_ATTEMPT = 1;

    private final ExtensionStepFunction<T> extensionFunction;
    private final ExtensionStepConfig<T> extensionConfig;

    public StepPrimitive(
            OperationIdentifier operationIdentifier,
            ExtensionStepFunction<T> function,
            TypeToken<T> resultTypeToken,
            ExtensionStepConfig<T> config,
            DurableContextImpl durableContext) {
        super(operationIdentifier, resultTypeToken, config.serDes(), durableContext);
        this.extensionFunction = function;
        this.extensionConfig = config;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        executeExtensionStepLogic(extensionConfig.initialState(), FIRST_ATTEMPT);
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED, FAILED -> markAlreadyCompleted();
            case PENDING -> pollReadyAndResumeExtensionStep();
            case STARTED -> {
                if (isAtMostOnce()) {
                    handleExtensionStepFailure(
                            new StepInterruptedException(existing), extensionState(existing), nextAttempt(existing));
                } else {
                    resumeExtensionStep(existing);
                }
            }
            case READY -> resumeExtensionStep(existing);
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected extension step status: " + existing.status());
        }
    }

    private void resumeExtensionStep(Operation existing) {
        executeExtensionStepLogic(extensionState(existing), nextAttempt(existing));
    }

    private int nextAttempt(Operation existing) {
        var details = existing.stepDetails();
        return details != null && details.attempt() != null ? details.attempt() + 1 : FIRST_ATTEMPT;
    }

    private T extensionState(Operation existing) {
        var details = existing.stepDetails();
        return details != null && details.result() != null
                ? deserializeResult(details.result())
                : extensionConfig.initialState();
    }

    private void pollReadyAndResumeExtensionStep() {
        pollForOperationUpdates()
                .thenCompose(op -> op.status() == OperationStatus.READY
                        ? CompletableFuture.completedFuture(op)
                        : pollForOperationUpdates())
                .thenAccept(this::resumeExtensionStep);
    }

    private void executeExtensionStepLogic(T state, int attempt) {
        Runnable userHandler = () -> {
            var stepContext = getContext().createStepContext(getOperationId(), getName(), attempt);
            try (var ignoredContext = BaseContextImpl.attachCurrentContext(stepContext);
                    var ignoredLogger = DurableLogger.attachContext()) {
                try {
                    checkpointStarted();
                    var result = runUserFunction(attempt, () -> extensionFunction.apply(state));
                    handleExtensionStepResult(result, attempt);
                } catch (Throwable e) {
                    handleExtensionStepFailure(e, state, attempt);
                }
            }
        };
        runUserHandler(userHandler, ThreadType.STEP);
    }

    private void handleExtensionStepResult(ExtensionStepResult<T> result, int attempt) {
        if (result == null) {
            throw new NullPointerException("Extension step function result cannot be null");
        }
        if (result instanceof ExtensionStepResult.Succeeded<T> succeeded) {
            handleStepSucceeded(succeeded.value());
            return;
        }
        var retry = (ExtensionStepResult.Retry<T>) result;
        handleExtensionStepRetry(retry, null, attempt);
    }

    private void handleExtensionStepRetry(ExtensionStepResult.Retry<T> retry, ErrorObject error, int attempt) {
        var serializedState = serializeAndDeserializeResult(retry.state());
        var retryDelaySeconds = Math.toIntExact(retry.delay().toSeconds());
        var update = OperationUpdate.builder()
                .action(OperationAction.RETRY)
                .payload(serializedState.serialized())
                .stepOptions(StepOptions.builder()
                        .nextAttemptDelaySeconds(retryDelaySeconds)
                        .build());
        if (error != null) {
            update.error(error);
        }
        sendOperationUpdate(update);
        pollReadyAndExecuteExtensionStep(serializedState.deserialized(), attempt + 1);
    }

    private void pollReadyAndExecuteExtensionStep(T state, int attempt) {
        pollForOperationUpdates()
                .thenCompose(op -> op.status() == OperationStatus.READY
                        ? CompletableFuture.completedFuture(op)
                        : pollForOperationUpdates())
                .thenRun(() -> executeExtensionStepLogic(state, attempt));
    }

    private void handleExtensionStepFailure(Throwable exception, T state, int attempt) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException suspendExecutionException) {
            throw suspendExecutionException;
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverable) {
            throw terminateExecution(unrecoverable);
        }
        var error = exception instanceof DurableOperationException durableOperationException
                ? durableOperationException.getErrorObject()
                : serializeException(exception);

        var retryStrategy = extensionConfig.retryStrategy();
        if (retryStrategy != null) {
            var decision = retryStrategy.makeRetryDecision(exception, state, attempt);
            if (decision instanceof ExtensionStepResult.Retry<T> retry) {
                handleExtensionStepRetry(retry, error, attempt);
                return;
            }
        }
        sendOperationUpdate(
                OperationUpdate.builder().action(OperationAction.FAIL).error(error));
    }

    private void checkpointStarted() {
        // Check if we need to send START
        var existing = getOperation();
        if (existing == null || existing.status() != OperationStatus.STARTED) {
            var startUpdate = OperationUpdate.builder().action(OperationAction.START);

            if (isAtMostOnce()) {
                // AT_MOST_ONCE: await START checkpoint before executing user code
                sendOperationUpdate(startUpdate);
            } else {
                // AT_LEAST_ONCE: fire-and-forget START checkpoint
                sendOperationUpdateAsync(startUpdate);
            }
        }
    }

    private void handleStepSucceeded(T result) {
        var serializedResult = serializeAndDeserializeResult(result);

        // Send SUCCEED
        var successUpdate =
                OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serializedResult.serialized());

        // sendOperationUpdate must be synchronous here. When waiting for the return of this call,
        // the context threads waiting for the result of this step operation will be wakened up and registered.
        sendOperationUpdate(successUpdate);
    }

    @Override
    public T get() {
        var op = waitForOperationCompletion();

        if (op.status() == OperationStatus.SUCCEEDED) {
            var stepDetails = op.stepDetails();
            var result = (stepDetails != null) ? stepDetails.result() : null;

            return deserializeResult(result);
        } else {
            var errorObject = op.stepDetails().error();

            // Throw StepInterruptedException directly for AT_MOST_ONCE interrupted steps
            if (StepInterruptedException.isStepInterruptedException(errorObject)) {
                throw new StepInterruptedException(op);
            }

            // Attempt to reconstruct and throw the original exception
            Throwable original = deserializeException(errorObject);
            if (original != null) {
                ExceptionHelper.sneakyThrow(original);
            }
            // Fallback: wrap in StepFailedException
            throw new StepFailedException(op);
        }
    }

    private boolean isAtMostOnce() {
        return extensionConfig.semanticsPerRetry() == ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY;
    }
}
