// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.DurableFuture;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.exception.SerDesException;
import com.amazonaws.lambda.durable.exception.UnrecoverableDurableExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ExecutionPhase;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.util.ExceptionHelper;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Phaser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/**
 * Base class for all durable operations (STEP, WAIT, etc.).
 *
 * <p>Key methods:
 *
 * <ul>
 *   <li>{@code execute()} starts the operation (returns immediately)
 *   <li>{@code get()} blocks until complete and returns the result
 * </ul>
 *
 * <p>The separation allows:
 *
 * <ul>
 *   <li>Starting multiple async operations quickly
 *   <li>Blocking on results later when needed
 *   <li>Proper thread coordination via Phasers
 * </ul>
 */
public abstract class BaseDurableOperation<T> implements DurableFuture<T> {
    private static final Logger logger = LoggerFactory.getLogger(BaseDurableOperation.class);

    private final String operationId;
    private final String name;
    private final OperationType operationType;
    private final ExecutionManager executionManager;
    private final TypeToken<T> resultTypeToken;
    private final SerDes resultSerDes;
    private final Phaser phaser;

    public BaseDurableOperation(
            String operationId,
            String name,
            OperationType operationType,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            ExecutionManager executionManager) {
        this.operationId = operationId;
        this.name = name;
        this.operationType = operationType;
        this.executionManager = executionManager;
        this.resultTypeToken = resultTypeToken;
        this.resultSerDes = resultSerDes;

        // todo: phaser could be used only in ExecutionManager and invisible from operations.
        this.phaser = executionManager.startPhaser(operationId);
    }

    /** Gets the unique identifier for this operation. */
    public String getOperationId() {
        return operationId;
    }

    /** Gets the operation name (maybe null). */
    public String getName() {
        return name;
    }

    /** Gets the operation type */
    public OperationType getType() {
        return operationType;
    }

    /** Starts the operation. Returns immediately after starting background work or checkpointing. Does not block. */
    public abstract void execute();

    /**
     * Blocks until the operation completes and returns the result.
     *
     * <p>Handles:
     *
     * <ul>
     *   <li>Thread deregistration (allows suspension)
     *   <li>Phaser blocking (waits for operation to complete)
     *   <li>Thread reactivation (resumes execution)
     *   <li>Result retrieval
     * </ul>
     *
     * @return the operation result
     */
    public abstract T get();

    /**
     * Gets the Operation from ExecutionManager and update the replay state from REPLAY to EXECUTE if operation is not
     * found
     *
     * @return the operation if found, otherwise null
     */
    protected Operation getOperation() {
        return executionManager.getOperationAndUpdateReplayState(getOperationId());
    }

    /**
     * check if it's called from a Step.
     *
     * @throws IllegalDurableOperationException if it's in a step
     */
    private void validateCurrentThreadType() {
        ThreadType current = executionManager.getCurrentContext().threadType();
        if (current == ThreadType.STEP) {
            var message = String.format(
                    "Nested %s operation is not supported on %s from within a %s execution.",
                    getType(), getName(), current);
            // terminate execution and throw the exception
            terminateExecutionWithIllegalDurableOperationException(message);
        }
    }

    // phase control utilities
    protected Operation waitForOperationCompletion() {

        validateCurrentThreadType();

        // If we are in a replay where the operation is already complete (SUCCEEDED /
        // FAILED), the Phaser will be
        // advanced in .execute() already and we don't block but return the result
        // immediately.
        if (phaser.getPhase() == ExecutionPhase.RUNNING.getValue()) {
            // Operation not done yet
            phaser.register();

            var context = executionManager.getCurrentContext();
            // Deregister current context - allows suspension
            logger.debug(
                    "get() on {} attempting to deregister context: {}",
                    getType(),
                    executionManager.getCurrentContext().contextId());
            deregisterActiveThreadAndUnsetCurrentContext(context.contextId());

            // Block until operation completes
            logger.trace("Waiting for operation to finish {} (Phaser: {})", getOperationId(), phaser);
            phaser.arriveAndAwaitAdvance();

            // Reactivate current context
            registerActiveThread(context.contextId(), context.threadType());
            setCurrentContext(context.contextId(), context.threadType());

            // Complete phase 1
            phaser.arriveAndDeregister();
        }

        // Get result based on status
        var op = getOperation();
        if (op == null) {
            terminateExecutionWithIllegalDurableOperationException(
                    String.format("%s operation not found: %s", getType(), getOperationId()));
        }
        return op;
    }

    protected void markAlreadyCompleted() {
        // Operation is already completed in a relay. We advance and deregister from the Phaser
        // so that get method doesn't block and returns the result immediately.
        logger.trace("Detected terminal status during replay. Advancing phaser 0 -> 1 {}.", phaser);
        phaser.arriveAndDeregister(); // Phase 0 -> 1
    }

    protected T terminateExecution(UnrecoverableDurableExecutionException exception) {
        executionManager.terminateExecution(exception);
        // Exception is already thrown from above. Keep the throw statement below to make tests happy
        throw exception;
    }

    protected T terminateExecutionWithIllegalDurableOperationException(String message) {
        return terminateExecution(new IllegalDurableOperationException(message));
    }

    // advanced phase control used by Step only
    protected void deregisterActiveThreadAndUnsetCurrentContext(String threadId) {
        executionManager.deregisterActiveThreadAndUnsetCurrentContext(threadId);
    }

    protected void registerActiveThread(String threadId, ThreadType threadType) {
        executionManager.registerActiveThread(threadId, threadType);
    }

    protected void setCurrentContext(String stepThreadId, ThreadType step) {
        executionManager.setCurrentContext(stepThreadId, step);
    }

    // polling and checkpointing
    protected void pollForOperationUpdates(Instant firstPoll, Duration duration) {
        executionManager.pollForOperationUpdates(operationId, firstPoll, duration);
    }

    protected void pollUntilReady(CompletableFuture<Void> pendingFuture, Instant firstPoll, Duration duration) {
        executionManager.pollUntilReady(operationId, pendingFuture, firstPoll, duration);
    }

    protected void sendOperationUpdate(OperationUpdate.Builder builder) {
        sendOperationUpdateAsync(builder).join();
    }

    protected CompletableFuture<Void> sendOperationUpdateAsync(OperationUpdate.Builder builder) {
        // todo: add parentId when we support operations in child context
        return executionManager.sendOperationUpdate(builder.id(operationId)
                .name(name)
                .type(operationType)
                .parentId(null)
                .build());
    }

    // serialization/deserialization utilities
    protected T deserializeResult(String result) {
        try {
            return resultSerDes.deserialize(result, resultTypeToken);
        } catch (SerDesException e) {
            logger.warn(
                    "Failed to deserialize {} result for operation name '{}'. Ensure the result is properly encoded.",
                    getType(),
                    getName());
            throw e;
        }
    }

    protected String serializeResult(T result) {
        return resultSerDes.serialize(result);
    }

    protected ErrorObject serializeException(Throwable throwable) {
        return ExceptionHelper.buildErrorObject(throwable, resultSerDes);
    }

    protected Throwable deserializeException(ErrorObject errorObject) {
        var errorType = errorObject.errorType();
        var errorData = errorObject.errorData();
        Throwable original = null;

        if (errorType == null) {
            return original;
        }
        try {

            Class<?> exceptionClass = Class.forName(errorType);
            if (Throwable.class.isAssignableFrom(exceptionClass)) {
                original =
                        resultSerDes.deserialize(errorData, TypeToken.get(exceptionClass.asSubclass(Throwable.class)));

                if (original != null) {
                    original.setStackTrace(ExceptionHelper.deserializeStackTrace(errorObject.stackTrace()));
                }
            }
        } catch (ClassNotFoundException e) {
            logger.warn("Cannot re-construct original exception type. Falling back to generic StepFailedException.");
        } catch (SerDesException e) {
            logger.warn("Cannot deserialize original exception data. Falling back to generic StepFailedException.", e);
        }
        return original;
    }

    /** Validates that current operation matches checkpointed operation during replay. */
    protected void validateReplay(Operation checkpointed) {
        if (checkpointed == null || checkpointed.type() == null) {
            return; // First execution, no validation needed
        }

        if (!checkpointed.type().equals(getType())) {
            terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation type mismatch for \"%s\". Expected %s, got %s",
                    operationId, checkpointed.type(), getType())));
        }

        if (!Objects.equals(checkpointed.name(), getName())) {
            terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation name mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                    operationId, checkpointed.name(), getName())));
        }
    }
}
