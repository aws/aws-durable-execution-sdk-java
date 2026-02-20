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
import com.amazonaws.lambda.durable.execution.ThreadContext;
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
    private final String parentId;
    private final OperationType operationType;
    private final ExecutionManager executionManager;
    private final TypeToken<T> resultTypeToken;
    private final SerDes resultSerDes;
    protected final CompletableFuture<Void> completionFuture;

    protected BaseDurableOperation(
            String operationId,
            String name,
            OperationType operationType,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            ExecutionManager executionManager,
            String parentId) {
        this.operationId = operationId;
        this.name = name;
        this.parentId = parentId;
        this.operationType = operationType;
        this.executionManager = executionManager;
        this.resultTypeToken = resultTypeToken;
        this.resultSerDes = resultSerDes;

        this.completionFuture = new CompletableFuture<>();

        // register this operation in ExecutionManager so that the operation can receive updates from ExecutionManager
        executionManager.registerOperation(this);
    }

    /** Convenience constructor for root-context operations where parentId is null. */
    public BaseDurableOperation(
            String operationId,
            String name,
            OperationType operationType,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            ExecutionManager executionManager) {
        this(operationId, name, operationType, resultTypeToken, resultSerDes, executionManager, null);
    }

    /** Gets the unique identifier for this operation. */
    public String getOperationId() {
        return operationId;
    }

    /** Gets the operation name (maybe null). */
    public String getName() {
        return name;
    }

    /** Gets the parent context ID. Null for root-context operations, set for child context operations. */
    protected String getParentId() {
        return parentId;
    }

    /** Gets the operation type */
    public OperationType getType() {
        return operationType;
    }

    /** Starts the operation. Returns immediately after starting background work or checkpointing. Does not block. */
    public abstract void execute();

    /**
     * Gets the Operation from ExecutionManager and update the replay state from REPLAY to EXECUTE if operation is not
     * found. Operation IDs are globally unique (prefixed for child contexts), so no parentId is needed for lookups.
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
        ThreadType current = getCurrentThreadContext().threadType();
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

        var threadContext = getCurrentThreadContext();

        // It's important that we synchronize access to the future. Otherwise, a race condition could happen if the
        // completionFuture is completed by a user thread (a step or child context thread) when the execution here
        // is between `isOperationCompleted` and `thenRun`.
        synchronized (completionFuture) {
            if (!isOperationCompleted()) {
                // Operation not done yet
                logger.trace(
                        "deregistering thread {} when waiting for operation {} ({}) to complete ({})",
                        threadContext.threadId(),
                        getOperation(),
                        getType(),
                        completionFuture);

                // Add a completion stage to completionFuture so that when the completionFuture is completed,
                // it will register the current Context thread synchronously to make sure it is always registered
                // strictly before the execution thread (Step or child context) is deregistered.
                completionFuture.thenRun(() -> registerActiveThread(threadContext.threadId()));

                // Deregister the current thread to allow suspension
                deregisterActiveThread(threadContext.threadId());
            }
        }

        // Block until operation completes. No-op if the future is already completed.
        completionFuture.join();

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
        if (ExecutionManager.isTerminalStatus(operation.status())) {
            // This method handles only terminal status updates. Override this method if a DurableOperation needs to
            // handle other updates.
            logger.trace("In onCheckpointComplete, completing operation {} ({})", operationId, completionFuture);
            // It's important that we synchronize access to the future, otherwise the processing could happen
            // on someone else's thread and cause a race condition.
            synchronized (completionFuture) {
                // Completing the future here will also run any other completion stages that have been attached
                // to the future. In our case, other contexts may have attached a function to reactivate themselves,
                // so they will definitely have a chance to reactivate before we finish completing and deactivating
                // whatever operations were just checkpointed.
                completionFuture.complete(null);
            }
        }
    }

    /** Marks the operation as already completed (in replay). */
    protected void markAlreadyCompleted() {
        // When the operation is already completed in a replay, we complete completionFuture immediately
        // so that the `get` method will be unblocked and the context thread will be registered
        logger.trace("In markAlreadyCompleted, completing operation: {} ({}).", operationId, completionFuture);

        // It's important that we synchronize access to the future, otherwise the processing could happen
        // on someone else's thread and cause a race condition.
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
    protected void deregisterActiveThread(String threadId) {
        executionManager.deregisterActiveThread(threadId);
    }

    protected void registerActiveThread(String threadId) {
        executionManager.registerActiveThread(threadId);
    }

    protected ThreadContext getCurrentThreadContext() {
        return executionManager.getCurrentThreadContext();
    }

    protected void setCurrentThreadContext(ThreadContext threadContext) {
        executionManager.setCurrentThreadContext(threadContext);
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
        return executionManager.sendOperationUpdate(builder.id(operationId)
                .name(name)
                .type(operationType)
                .parentId(parentId)
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
}
