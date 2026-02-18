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
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.util.ExceptionHelper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
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
 *   <li>Proper thread coordination via future
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
    private final CompletableFuture<Void> completionFuture;

    protected BaseDurableOperation(
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

        this.completionFuture = new CompletableFuture<>();

        // register this operation in ExecutionManager so that the operation can receive updates from ExecutionManager
        executionManager.registerOperation(this);
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
     * Gets the Operation from ExecutionManager and update the replay state from REPLAY to EXECUTE if operation is not
     * found
     *
     * @return the operation if found, otherwise null
     */
    protected Operation getOperation() {
        return executionManager.getOperationAndUpdateReplayState(getOperationId());
    }

    /**
     * Checks if it's called from a Step.
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

    /** Checks if this operation is completed */
    protected boolean isOperationCompleted() {
        return completionFuture.isDone();
    }

    /** Waits for the operation to complete and suspends the execution if no active thread is running */
    protected Operation waitForOperationCompletion() {

        validateCurrentThreadType();

        var context = executionManager.getCurrentContext();

        // Use a synchronized block here to prevent the completionFuture from being completed by the execution thread
        // (a step or child context thread) when it's inside the `if` block where the completion check is done (not
        // completed) while the callback isn't added to the completionFuture or the current thread isn't deregistered.
        synchronized (completionFuture) {
            if (!isOperationCompleted()) {
                // Operation not done yet
                logger.debug("get() on {} attempting to deregister context: {}", getType(), context.contextId());

                // Add a callback to completionFuture so that when the completionFuture is completed,
                // it will register the current Context thread synchronously to make sure it is always registered
                // before the execution thread (Step or child context) is deregistered.
                completionFuture.thenRun(() -> registerActiveThread(context.contextId(), context.threadType()));

                // Deregister the current thread to allow suspension
                deregisterActiveThreadAndUnsetCurrentContext(context.contextId());
            }
        }

        // Block until operation completes. No-op if the future is already completed.
        logger.trace("Waiting for operation to finish {} ({})", getOperationId(), completionFuture);
        completionFuture.join();

        // Reactivate current context. No-op if this is called twice.
        setCurrentContext(context.contextId(), context.threadType());

        // Get result based on status
        var op = getOperation();
        if (op == null) {
            terminateExecutionWithIllegalDurableOperationException(
                    String.format("%s operation not found: %s", getType(), getOperationId()));
        }
        return op;
    }

    /** Receives operation updates from ExecutionManager and updates the internal state of the operation */
    public void onCheckpointComplete(Operation operation) {
        if (isTerminalStatus(operation.status())) {
            synchronized (completionFuture) {
                logger.trace("In onCheckpointComplete, completing operation {} ({})", operationId, completionFuture);
                completionFuture.complete(null);
            }
        }
    }

    /** Marks the operation as already completed (in replay). */
    protected void markAlreadyCompleted() {
        // When the operation is already completed in a replay, we complete completionFuture immediately
        // so that the `get` method will be unblocked and the context thread will be registered
        logger.trace("In markAlreadyCompleted, completing operation: {} ({}).", operationId, completionFuture);
        synchronized (completionFuture) {
            completionFuture.complete(null);
        }
    }

    // terminate the execution
    protected T terminateExecution(UnrecoverableDurableExecutionException exception) {
        executionManager.terminateExecution(exception);
        // Exception is already thrown from above. Keep the throw statement below to make tests happy
        throw exception;
    }

    protected T terminateExecutionWithIllegalDurableOperationException(String message) {
        return terminateExecution(new IllegalDurableOperationException(message));
    }

    // advanced thread and context control
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
    protected CompletableFuture<Operation> pollForOperationUpdates() {
        return executionManager.pollForOperationUpdates(operationId);
    }

    protected CompletableFuture<Operation> pollForOperationUpdates(Duration delay) {
        return executionManager.pollForOperationUpdates(operationId, delay);
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
        Throwable original = null;
        if (errorObject == null) {
            return original;
        }
        var errorType = errorObject.errorType();
        var errorData = errorObject.errorData();

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

    private boolean isTerminalStatus(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED
                || status == OperationStatus.FAILED
                || status == OperationStatus.CANCELLED
                || status == OperationStatus.TIMED_OUT
                || status == OperationStatus.STOPPED;
    }
}
