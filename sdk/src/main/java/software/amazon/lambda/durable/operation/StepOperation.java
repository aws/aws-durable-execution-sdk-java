// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepOptions;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
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
public class StepOperation<T> extends SerializableDurableOperation<T> {
    private static final Integer FIRST_ATTEMPT = 1;

    private final Function<StepContext, T> function;
    private final StepConfig config;

    public StepOperation(
            OperationIdentifier operationIdentifier,
            Function<StepContext, T> function,
            TypeToken<T> resultTypeToken,
            StepConfig config,
            DurableContextImpl durableContext) {
        super(operationIdentifier, resultTypeToken, config.serDes(), durableContext);

        this.function = function;
        this.config = config;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        executeStepLogic(FIRST_ATTEMPT);
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        var attempt = existing.stepDetails() != null && existing.stepDetails().attempt() != null
                ? existing.stepDetails().attempt() + 1
                : FIRST_ATTEMPT;
        switch (existing.status()) {
            case SUCCEEDED, FAILED -> markAlreadyCompleted();
            case STARTED -> {
                if (config.semantics() == StepSemantics.AT_MOST_ONCE_PER_RETRY) {
                    // AT_MOST_ONCE: treat as interrupted, go through retry logic
                    handleStepFailure(new StepInterruptedException(existing), attempt);
                } else {
                    // AT_LEAST_ONCE: re-execute the step
                    executeStepLogic(attempt);
                }
            }
            // Step is pending retry - Start polling for PENDING -> READY transition
            case PENDING -> {
                if (existing.stepDetails() != null && existing.stepDetails().nextAttemptTimestamp() != null) {
                    pollReadyAndExecuteStepLogic(existing.stepDetails().nextAttemptTimestamp(), attempt);
                } else {
                    throw terminateExecutionWithIllegalDurableOperationException(
                            "Unexpected PENDING step without nextAttemptTimestamp: " + getOperationId());
                }
            }
            // Execute with current attempt
            case READY -> executeStepLogic(attempt);
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected step status: " + existing.status());
        }
    }

    private void pollReadyAndExecuteStepLogic(Instant nextAttemptInstant, int attempt) {
        pollForOperationUpdates(nextAttemptInstant)
                .thenCompose(op -> op.status() == OperationStatus.READY
                        ? CompletableFuture.completedFuture(op)
                        : pollForOperationUpdates(nextAttemptInstant))
                .thenRun(() -> executeStepLogic(attempt));
    }

    private void executeStepLogic(int attempt) {
        Runnable userHandler = () -> {
            // use a try-with-resources to
            // - add thread id/type to thread local when the step starts
            // - clear logger properties when the step finishes
            try (StepContext stepContext = getContext().createStepContext(getOperationId(), getName(), attempt)) {
                try {
                    checkpointStarted();

                    // Execute the function
                    T result = function.apply(stepContext);

                    handleStepSucceeded(result);
                } catch (Throwable e) {
                    handleStepFailure(e, attempt);
                }
            }
        };

        // Execute user provided step code in user-configured executor
        runUserHandler(userHandler, ThreadType.STEP);
    }

    private void checkpointStarted() {
        // Check if we need to send START
        var existing = getOperation();
        if (existing == null || existing.status() != OperationStatus.STARTED) {
            var startUpdate = OperationUpdate.builder().action(OperationAction.START);

            if (config.semantics() == StepSemantics.AT_MOST_ONCE_PER_RETRY) {
                // AT_MOST_ONCE: await START checkpoint before executing user code
                sendOperationUpdate(startUpdate);
            } else {
                // AT_LEAST_ONCE: fire-and-forget START checkpoint
                sendOperationUpdateAsync(startUpdate);
            }
        }
    }

    private void handleStepSucceeded(T result) {
        // Send SUCCEED
        var successUpdate =
                OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serializeResult(result));

        // sendOperationUpdate must be synchronous here. When waiting for the return of this call,
        // the context threads waiting for the result of this step operation will be wakened up and registered.
        sendOperationUpdate(successUpdate);
    }

    private void handleStepFailure(Throwable exception, int attempt) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException suspendExecutionException) {
            throw suspendExecutionException;
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
            // terminate the execution and throw the exception if it's not recoverable
            throw terminateExecution(unrecoverableDurableExecutionException);
        }

        final ErrorObject errorObject;
        if (exception instanceof DurableOperationException durableOperationException) {
            errorObject = durableOperationException.getErrorObject();
        } else {
            errorObject = serializeException(exception);
        }

        var isRetryable = !(exception instanceof StepInterruptedException);
        var retryDecision = config.retryStrategy().makeRetryDecision(exception, attempt);

        if (isRetryable && retryDecision.shouldRetry()) {
            // Send RETRY
            var retryDelayInSeconds = Math.toIntExact(retryDecision.delay().toSeconds());
            var retryUpdate = OperationUpdate.builder()
                    .action(OperationAction.RETRY)
                    .error(errorObject)
                    .stepOptions(StepOptions.builder()
                            // RetryDecisions always produce integer number of seconds greater or equals to
                            // 1 (no sub-second numbers)
                            .nextAttemptDelaySeconds(retryDelayInSeconds)
                            .build());
            sendOperationUpdate(retryUpdate);

            // Poll for READY status and then execute the step again
            pollReadyAndExecuteStepLogic(Instant.now().plusSeconds(retryDelayInSeconds), attempt + 1);
        } else {
            // Send FAIL - retries exhausted
            var failUpdate =
                    OperationUpdate.builder().action(OperationAction.FAIL).error(errorObject);
            sendOperationUpdate(failUpdate);
        }
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
}
