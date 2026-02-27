// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepOptions;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepConfig;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.StepSemantics;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.util.ExceptionHelper;

public class StepOperation<T> extends BaseDurableOperation<T> {

    private final Function<StepContext, T> function;
    private final StepConfig config;
    private final ExecutorService userExecutor;

    public StepOperation(
            String operationId,
            String name,
            Function<StepContext, T> function,
            TypeToken<T> resultTypeToken,
            StepConfig config,
            DurableContext durableContext) {
        super(operationId, name, OperationType.STEP, resultTypeToken, config.serDes(), durableContext);

        this.function = function;
        this.config = config;
        this.userExecutor = durableContext.getDurableConfig().getExecutorService();
    }

    @Override
    public void execute() {
        var existing = getOperation();

        if (existing != null) {
            validateReplay(existing);
            // This means we are in a replay scenario
            switch (existing.status()) {
                case SUCCEEDED, FAILED -> markAlreadyCompleted();
                case STARTED -> {
                    var attempt = existing.stepDetails().attempt() != null
                            ? existing.stepDetails().attempt()
                            : 0;
                    if (config.semantics() == StepSemantics.AT_MOST_ONCE_PER_RETRY) {
                        // AT_MOST_ONCE: treat as interrupted, go through retry logic
                        handleStepFailure(new StepInterruptedException(existing), attempt + 1);
                    } else {
                        // AT_LEAST_ONCE: re-execute the step
                        executeStepLogic(attempt);
                    }
                }
                // Step is pending retry - Start polling for PENDING -> READY transition
                case PENDING ->
                    pollReadyAndExecuteStepLogic(existing.stepDetails().attempt());
                // Execute with current attempt
                case READY -> executeStepLogic(existing.stepDetails().attempt());
                default ->
                    terminateExecutionWithIllegalDurableOperationException(
                            "Unexpected step status: " + existing.status());
            }
        } else {
            // First execution
            executeStepLogic(0);
        }
    }

    private CompletableFuture<Void> pollReadyAndExecuteStepLogic(int attempt) {
        return pollForOperationUpdates()
                .thenCompose(op -> op.status() == OperationStatus.READY
                        ? CompletableFuture.completedFuture(op)
                        : pollForOperationUpdates())
                .thenRun(() -> executeStepLogic(attempt));
    }

    private void executeStepLogic(int attempt) {
        var stepThreadId = getThreadId();

        // Register step thread as active BEFORE executor runs (prevents suspension when handler deregisters)
        // thread local ThreadContext is set inside the executor since that's where the step actually runs
        registerActiveThread(stepThreadId);

        // Execute user code in customer-configured executor
        userExecutor.execute(() -> {
            StepContext stepContext = getContext().createStepContext(getOperationId());
            // Set thread local ThreadContext on the executor thread
            setCurrentThreadContext(new ThreadContext(stepThreadId, ThreadType.STEP));
            // Set operation context for logging in this thread
            stepContext.getLogger().setOperationContext(getOperationId(), getName(), attempt);
            try {
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

                // Execute the function
                T result = function.apply(stepContext);

                // Send SUCCEED
                var successUpdate = OperationUpdate.builder()
                        .action(OperationAction.SUCCEED)
                        .payload(serializeResult(result));

                // sendOperationUpdate must be synchronous here. When waiting for the return of this call, the context
                // threads waiting for the result of this step operation will be wakened up and registered.
                sendOperationUpdate(successUpdate);
            } catch (Throwable e) {
                handleStepFailure(e, attempt);
            } finally {
                try {
                    deregisterActiveThread(stepThreadId);
                } catch (SuspendExecutionException e) {
                    // Expected when this is the last active thread. Must catch here because:
                    // 1/ This runs in a worker thread detached from handlerFuture
                    // 2/ Uncaught exception would prevent stepAsync().get() from resume
                    // Suspension/Termination is already signaled via suspendExecutionFuture/terminateExecutionFuture
                    // before the throw.
                }
                stepContext.getLogger().clearOperationContext();
            }
        });
    }

    private void handleStepFailure(Throwable exception, int attempt) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof UnrecoverableDurableExecutionException) {
            // terminate the execution and throw the exception if it's not recoverable
            terminateExecution((UnrecoverableDurableExecutionException) exception);
        }

        final ErrorObject errorObject;
        if (exception instanceof DurableOperationException) {
            errorObject = ((DurableOperationException) exception).getErrorObject();
        } else {
            errorObject = serializeException(exception);
        }

        var isRetryable = !(exception instanceof StepInterruptedException);
        var retryDecision = config.retryStrategy().makeRetryDecision(exception, attempt);

        if (isRetryable && retryDecision.shouldRetry()) {
            // Send RETRY
            var retryUpdate = OperationUpdate.builder()
                    .action(OperationAction.RETRY)
                    .error(errorObject)
                    .stepOptions(StepOptions.builder()
                            // RetryDecisions always produce integer number of seconds greater or equals to
                            // 1 (no sub-second numbers)
                            .nextAttemptDelaySeconds(
                                    Math.toIntExact(retryDecision.delay().toSeconds()))
                            .build());
            sendOperationUpdate(retryUpdate);

            pollReadyAndExecuteStepLogic(attempt + 1);
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
