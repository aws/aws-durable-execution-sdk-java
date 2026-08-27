// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
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
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
import software.amazon.lambda.durable.logging.DurableLogger;
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
            PrimitiveOperationIdentifier operationIdentifier,
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
            case PENDING -> {
                var details = existing.stepDetails();
                if (details == null || details.nextAttemptTimestamp() == null) {
                    throw terminateExecutionWithIllegalDurableOperationException(
                            "Unexpected PENDING step without nextAttemptTimestamp: " + getOperationId());
                }
                pollReadyAndResumeExtensionStep(details.nextAttemptTimestamp());
            }
            case STARTED -> {
                if (isAtMostOnce()) {
                    failInterruptedStep(existing);
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

    private void failInterruptedStep(Operation existing) {
        var state = extensionState(existing);
        var attempt = nextAttempt(existing);
        runUserHandlerAsync(
                () -> handleExtensionStepFailure(new StepInterruptedException(existing), state, attempt),
                (ignored, throwable) -> throwable == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(throwable),
                ThreadType.STEP,
                false);
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

    private void pollReadyAndResumeExtensionStep(Instant nextAttemptTimestamp) {
        pollForOperationUpdates(nextAttemptTimestamp)
                .thenCompose(op -> op.status() == OperationStatus.READY
                        ? CompletableFuture.completedFuture(op)
                        : pollForOperationUpdates(nextAttemptTimestamp))
                .thenAccept(this::resumeExtensionStep);
    }

    private void executeExtensionStepLogic(T state, int attempt) {
        var stepContext = getContext().createStepContext(getOperationId(), getName(), attempt);
        runUserHandlerAsync(
                () -> {
                    try (var ignoredContext = BaseContextImpl.attachCurrentContext(stepContext);
                            var ignoredLogger = DurableLogger.attachContext()) {
                        checkpointStarted();
                        return runUserFunctionAsync(attempt, () -> extensionFunction.apply(state));
                    }
                },
                (result, throwable) -> {
                    try (var ignoredContext = BaseContextImpl.attachCurrentContext(stepContext);
                            var ignoredLogger = DurableLogger.attachContext()) {
                        return handleExtensionStepCompletion(result, throwable, state, attempt);
                    }
                },
                ThreadType.STEP,
                false);
    }

    private CompletionStage<Void> handleExtensionStepCompletion(
            ExtensionStepResult<T> result, Throwable throwable, T state, int attempt) {
        if (throwable != null) {
            return handleExtensionStepFailure(throwable, state, attempt);
        }

        final CompletionStage<Void> success;
        try {
            success = handleExtensionStepResult(result, attempt);
        } catch (Throwable failure) {
            return handleExtensionStepFailure(failure, state, attempt);
        }
        return success.handle((ignored, failure) -> failure == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : handleExtensionStepFailure(failure, state, attempt))
                .thenCompose(Function.identity());
    }

    private CompletionStage<Void> handleExtensionStepResult(ExtensionStepResult<T> result, int attempt) {
        if (result == null) {
            return CompletableFuture.failedFuture(
                    new NullPointerException("Extension step function result cannot be null"));
        }
        if (result instanceof ExtensionStepResult.Succeeded<T> succeeded) {
            return handleStepSucceeded(succeeded.value());
        }
        if (result instanceof ExtensionStepResult.Retry<T> retry) {
            return handleExtensionStepRetry(retry.state(), ignored -> retry.delay(), null, attempt);
        }
        var retry = (ExtensionStepResult.RetryAfterNormalization<T>) result;
        return handleExtensionStepRetry(retry.state(), retry::delay, null, attempt);
    }

    private CompletionStage<Void> handleExtensionStepRetry(
            ExtensionStepResult.Retry<T> retry, ErrorObject error, int attempt) {
        return handleExtensionStepRetry(retry.state(), ignored -> retry.delay(), error, attempt);
    }

    private CompletionStage<Void> handleExtensionStepRetry(
            T state, Function<T, Duration> delayStrategy, ErrorObject error, int attempt) {
        final SerializedResult<T> serializedState;
        final int retryDelaySeconds;
        try {
            serializedState = serializeAndDeserializeResult(state);
            var delay = delayStrategy.apply(serializedState.deserialized());
            retryDelaySeconds = Math.toIntExact(delay.toSeconds());
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
        var update = OperationUpdate.builder()
                .action(OperationAction.RETRY)
                .payload(serializedState.serialized())
                .stepOptions(StepOptions.builder()
                        .nextAttemptDelaySeconds(retryDelaySeconds)
                        .build());
        if (error != null) {
            update.error(error);
        }
        return sendOperationUpdateAsync(update)
                .thenRun(() -> pollReadyAndExecuteExtensionStep(
                        serializedState.deserialized(),
                        attempt + 1,
                        Instant.now().plusSeconds(retryDelaySeconds)));
    }

    private void pollReadyAndExecuteExtensionStep(T state, int attempt, Instant nextAttemptTimestamp) {
        pollForOperationUpdates(nextAttemptTimestamp)
                .thenCompose(op -> op.status() == OperationStatus.READY
                        ? CompletableFuture.completedFuture(op)
                        : pollForOperationUpdates(nextAttemptTimestamp))
                .thenRun(() -> executeExtensionStepLogic(state, attempt));
    }

    private CompletionStage<Void> handleExtensionStepFailure(Throwable exception, T state, int attempt) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException) {
            return CompletableFuture.failedFuture(exception);
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverable) {
            try {
                terminateExecution(unrecoverable);
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        final ErrorObject error;
        try {
            error = exception instanceof DurableOperationException durableOperationException
                    ? durableOperationException.getErrorObject()
                    : serializeException(exception);
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }

        var retryStrategy = extensionConfig.retryStrategy();
        if (retryStrategy != null) {
            var decision = retryStrategy.makeRetryDecision(exception, state, attempt);
            if (decision instanceof ExtensionStepResult.Retry<T> retry) {
                return handleExtensionStepRetry(retry, error, attempt);
            }
        }
        return sendOperationUpdateAsync(
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

    private CompletionStage<Void> handleStepSucceeded(T result) {
        final SerializedResult<T> serializedResult;
        try {
            serializedResult = serializeAndDeserializeResult(result);
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }

        var successUpdate =
                OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serializedResult.serialized());
        return sendOperationUpdateAsync(successUpdate);
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
