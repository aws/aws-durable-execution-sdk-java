// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepOptions;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.WaitForConditionResult;
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
public class WaitForConditionOperation<T> extends SerializableDurableOperation<T> {
    private static final Integer FIRST_ATTEMPT = 1;

    private final BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc;
    private final WaitForConditionConfig<T> config;

    public WaitForConditionOperation(
            OperationIdentifier operationIdentifier,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            TypeToken<T> resultTypeToken,
            WaitForConditionConfig<T> config,
            DurableContextImpl durableContext) {
        super(operationIdentifier, resultTypeToken, config.serDes(), durableContext);

        this.checkFunc = checkFunc;
        this.config = config;
    }

    @Override
    protected void start() {
        // Round-trip through SerDes so the first check observes the same shape as every subsequent
        // check, which always deserializes state from a checkpoint (see resumeCheckLoop below).
        var initialState = serializeAndDeserializeResult(config.initialState()).deserialized();
        executeCheckLogic(initialState, FIRST_ATTEMPT);
    }

    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED, FAILED -> markAlreadyCompleted(); // Check if already completed / failed
            case PENDING -> pollReadyAndResumeCheckLoop(existing); // Check if pending retry
            case STARTED, READY -> resumeCheckLoop(existing);
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
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
            // Fallback: wrap in WaitForConditionFailedException
            throw new WaitForConditionFailedException(op);
        }
    }

    private void resumeCheckLoop(Operation existing) {
        var stepDetails = existing.stepDetails();
        int attempt =
                (stepDetails != null && stepDetails.attempt() != null) ? stepDetails.attempt() + 1 : FIRST_ATTEMPT;
        var checkpointData = stepDetails != null ? stepDetails.result() : null;
        T currentState; // Get current state
        if (checkpointData != null) {
            currentState = deserializeResult(checkpointData);
        } else {
            currentState = config.initialState();
        }
        executeCheckLogic(currentState, attempt);
    }

    private CompletableFuture<Void> pollReadyAndResumeCheckLoop(Operation existing) {
        return pollUntilReady().thenAccept(op -> {
            if (!isOperationCompleted() && op.status() == OperationStatus.READY) resumeCheckLoop(op);
        });
    }

    private CompletableFuture<Operation> pollUntilReady() {
        var known = getOperation();
        if (isReadyOrTerminal(known)) return CompletableFuture.completedFuture(known);
        var update = pollForOperationUpdates();
        // Register before re-reading: another checkpoint may already have delivered READY before this poll existed.
        known = getOperation();
        if (isReadyOrTerminal(known)) {
            update.complete(known);
            return CompletableFuture.completedFuture(known);
        }
        return update.thenCompose(
                op -> isReadyOrTerminal(op) ? CompletableFuture.completedFuture(op) : pollUntilReady());
    }

    private static boolean isReadyOrTerminal(Operation operation) {
        return operation != null
                && (operation.status() == OperationStatus.READY
                        || operation.status() == OperationStatus.SUCCEEDED
                        || operation.status() == OperationStatus.FAILED);
    }

    private void executeCheckLogic(T currentState, int attempt) {
        var publishedWorker = new CompletableFuture<CompletableFuture<?>>();
        runUserHandler(() -> runCheckLoop(currentState, attempt, publishedWorker), ThreadType.STEP);
        publishedWorker.complete(getRunningUserHandler());
    }

    private void runCheckLoop(T currentState, int attempt, CompletableFuture<CompletableFuture<?>> publishedWorker) {
        while (!isOperationCompleted()) {
            var stepContext = getContext().createStepContext(getOperationId(), getName(), attempt);
            BaseContextImpl.setCurrentContext(stepContext);
            try (var ignored = DurableLogger.attachContext()) {
                try {
                    var existing = getOperation();
                    if (existing == null || existing.status() != OperationStatus.STARTED) {
                        sendOperationUpdateAsync(OperationUpdate.builder().action(OperationAction.START));
                    }
                    var stateForCheck = currentState;
                    var result = runUserFunction(attempt, () -> checkFunc.apply(stateForCheck, stepContext));
                    var serializedState = serializeAndDeserializeResult(result.value());
                    var deserializedValue = serializedState.deserialized();
                    if (result.isDone()) {
                        sendOperationUpdate(OperationUpdate.builder()
                                .action(OperationAction.SUCCEED)
                                .payload(serializedState.serialized()));
                        return;
                    }
                    Duration delay = config.waitStrategy().evaluate(deserializedValue, attempt);
                    sendOperationUpdate(OperationUpdate.builder()
                            .action(OperationAction.RETRY)
                            .payload(serializedState.serialized())
                            .stepOptions(StepOptions.builder()
                                    .nextAttemptDelaySeconds(Math.toIntExact(delay.toSeconds()))
                                    .build()));
                    var inlineReady =
                            continueInlineOrAfterCurrentWorker(publishedWorker, deserializedValue, attempt + 1);
                    if (inlineReady == null || inlineReady.status() != OperationStatus.READY) return;
                    // READY can be present in the RETRY response. Keep this worker active and continue without
                    // recursively starting an overlapping handler or suspending executable work.
                    currentState = deserializedValue;
                    attempt++;
                } catch (Throwable failure) {
                    handleCheckFailure(failure);
                    return;
                }
            }
        }
    }

    private Operation continueInlineOrAfterCurrentWorker(
            CompletableFuture<CompletableFuture<?>> publishedWorker, T nextState, int nextAttempt) {
        var owner = Thread.currentThread();
        var inline = new AtomicReference<Operation>();
        var acceptingInline = new AtomicBoolean(true);
        try {
            pollUntilReady().thenAccept(op -> {
                if (Thread.currentThread() == owner && acceptingInline.get()) {
                    inline.set(op);
                } else {
                    // A checkpoint callback can observe READY before this attempt's worker exits. Its checkpoint
                    // processing lease stays active through this handoff, preventing a false quiescence window.
                    publishedWorker.join().join();
                    if (!isOperationCompleted() && op.status() == OperationStatus.READY)
                        executeCheckLogic(nextState, nextAttempt);
                }
            });
        } finally {
            acceptingInline.set(false);
        }
        return inline.get();
    }

    private void handleCheckFailure(Throwable exception) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException suspendExecutionException) {
            throw suspendExecutionException;
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverable) {
            throw terminateExecution(unrecoverable);
        }

        final var errorObject = (exception instanceof DurableOperationException durableOpEx)
                ? durableOpEx.getErrorObject()
                : serializeException(exception);

        // Checkpoint FAIL
        var failUpdate = OperationUpdate.builder().action(OperationAction.FAIL).error(errorObject);
        sendOperationUpdate(failUpdate);
    }
}
