// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepOptions;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.WaitForConditionConfig;
import software.amazon.lambda.durable.WaitForConditionDecision;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.exception.WaitForConditionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Durable operation that periodically checks a user-supplied condition function, using a configurable wait strategy to
 * determine polling intervals and termination.
 *
 * <p>Uses {@link OperationType#STEP} with {@link OperationSubType#WAIT_FOR_CONDITION} subtype. Each polling iteration
 * is checkpointed as a RETRY on the same STEP operation.
 *
 * @param <T> the type of state being polled
 */
public class WaitForConditionOperation<T> extends BaseDurableOperation<T> {

    private final BiFunction<T, StepContext, T> checkFunc;
    private final WaitForConditionConfig<T> config;
    private final ExecutorService userExecutor;

    public WaitForConditionOperation(
            String operationId,
            String name,
            BiFunction<T, StepContext, T> checkFunc,
            TypeToken<T> resultTypeToken,
            WaitForConditionConfig<T> config,
            DurableContext durableContext) {
        super(
                OperationIdentifier.of(operationId, name, OperationType.STEP, OperationSubType.WAIT_FOR_CONDITION),
                resultTypeToken,
                config.serDes(),
                durableContext);

        this.checkFunc = checkFunc;
        this.config = config;
        this.userExecutor = durableContext.getDurableConfig().getExecutorService();
    }

    @Override
    protected void start() {
        executeCheckLogic(config.initialState(), 0);
    }

    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED, FAILED -> markAlreadyCompleted(); // Check if already completed / failed
            case PENDING -> pollReadyAndResumeCheckLoop(existing); // Check if pending retry
            case STARTED, READY -> resumeCheckLoop(existing);
            default ->
                terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected waitForCondition status: " + existing.status());
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

            // Attempt to reconstruct and throw the original exception
            Throwable original = deserializeException(errorObject);
            if (original != null) {
                ExceptionHelper.sneakyThrow(original);
            }
            // Fallback: wrap in WaitForConditionException
            throw new WaitForConditionException(op);
        }
    }

    private void resumeCheckLoop(Operation existing) {
        var stepDetails = existing.stepDetails();
        int attempt = (stepDetails != null && stepDetails.attempt() != null) ? stepDetails.attempt() : 0;
        var checkpointData = stepDetails != null ? stepDetails.result() : null;
        T currentState; // Get current state
        if (checkpointData != null) {
            try {
                currentState = deserializeResult(checkpointData);
            } catch (Exception e) {
                currentState = config.initialState();
            }
        } else {
            currentState = config.initialState();
        }
        executeCheckLogic(currentState, attempt);
    }

    private CompletableFuture<Void> pollReadyAndResumeCheckLoop(Operation existing) {
        return pollForOperationUpdates()
                .thenCompose(op -> op.status() == OperationStatus.READY
                        ? CompletableFuture.completedFuture(op)
                        : pollForOperationUpdates())
                .thenRun(() -> resumeCheckLoop(existing));
    }

    private void executeCheckLogic(T currentState, int attempt) {
        // Register thread as active BEFORE executor runs
        registerActiveThread(getOperationId());

        CompletableFuture.runAsync(
                () -> {
                    try (var stepContext = getContext().createStepContext(getOperationId(), getName(), attempt)) {
                        try {
                            // Checkpoint START if not already started
                            var existing = getOperation();
                            if (existing == null || existing.status() != OperationStatus.STARTED) {
                                var startUpdate = OperationUpdate.builder().action(OperationAction.START);
                                sendOperationUpdateAsync(startUpdate);
                            }

                            // Execute check function in user executor
                            T newState = checkFunc.apply(currentState, stepContext);

                            // Serialize/deserialize round-trip to ensure state is checkpoint-safe
                            var serializedState = serializeResult(newState);
                            T deserializedState = deserializeResult(serializedState);

                            // Evaluate wait strategy
                            var decision = config.waitStrategy().evaluate(deserializedState, attempt);

                            handleDecision(decision, serializedState, attempt);
                        } catch (Throwable e) {
                            handleCheckFailure(e);
                        }
                    }
                },
                userExecutor);
    }

    private void handleDecision(WaitForConditionDecision decision, String serializedState, int attempt) {
        if (!decision.shouldContinue()) {
            // Condition met — checkpoint SUCCEED
            var successUpdate =
                    OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serializedState);
            sendOperationUpdate(successUpdate);
        } else {
            // Checkpoint RETRY with delay
            var retryUpdate = OperationUpdate.builder()
                    .action(OperationAction.RETRY)
                    .payload(serializedState)
                    .stepOptions(StepOptions.builder()
                            .nextAttemptDelaySeconds(
                                    Math.toIntExact(decision.delay().toSeconds()))
                            .build());
            sendOperationUpdate(retryUpdate);

            // Poll for READY, then continue the loop
            pollForOperationUpdates()
                    .thenCompose(op -> op.status() == OperationStatus.READY
                            ? CompletableFuture.completedFuture(op)
                            : pollForOperationUpdates())
                    .thenRun(() -> executeCheckLogic(deserializeResult(serializedState), attempt + 1));
        }
    }

    private void handleCheckFailure(Throwable exception) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException) {
            ExceptionHelper.sneakyThrow(exception);
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverable) {
            terminateExecution(unrecoverable);
        }

        final var errorObject = (exception instanceof DurableOperationException durableOpEx)
                ? durableOpEx.getErrorObject()
                : serializeException(exception);

        // Checkpoint FAIL
        var failUpdate = OperationUpdate.builder().action(OperationAction.FAIL).error(errorObject);
        sendOperationUpdate(failUpdate);
    }
}
