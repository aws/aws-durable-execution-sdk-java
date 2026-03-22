// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

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

    private final OperationIdentifier operationIdentifier;
    private final ExecutionManager executionManager;
    private final TypeToken<T> resultTypeToken;
    private final SerDes resultSerDes;
    protected final CompletableFuture<Void> completionFuture;
    private final DurableContextImpl durableContext;

    /**
     * Constructs a new durable operation.
     *
     * @param operationIdentifier the unique identifier for this operation
     * @param resultTypeToken the type token for deserializing the result
     * @param resultSerDes the serializer/deserializer for the result
     * @param durableContext the parent context this operation belongs to
     */
    protected BaseDurableOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext) {
        this.operationIdentifier = operationIdentifier;
        this.durableContext = durableContext;
        this.executionManager = durableContext.getExecutionManager();
        this.resultTypeToken = resultTypeToken;
        this.resultSerDes = resultSerDes;

        this.completionFuture = new CompletableFuture<>();

        // register this operation in ExecutionManager so that the operation can receive updates from ExecutionManager
        executionManager.registerOperation(this);
    }

    /** Gets the operation sub-type (e.g. RUN_IN_CHILD_CONTEXT, WAIT_FOR_CALLBACK). */
    public OperationSubType getSubType() {
        return operationIdentifier.subType();
    }

    /** Gets the unique identifier for this operation. */
    public String getOperationId() {
        return operationIdentifier.operationId();
    }

    /** Gets the operation name (may be null). */
    public String getName() {
        return operationIdentifier.name();
    }

    /** Gets the parent context. */
    protected DurableContextImpl getContext() {
        return durableContext;
    }

    /** Gets the operation type. */
    public OperationType getType() {
        return operationIdentifier.operationType();
    }

    /**
     * Starts the operation by checking for an existing checkpoint. If a checkpoint exists, validates and replays it;
     * otherwise starts fresh execution.
     */
    public void execute() {
        var existing = getOperation();

        if (existing != null) {
            validateReplay(existing);
            replay(existing);
        } else {
            if (durableContext.isReplaying()) {
                this.durableContext.setExecutionMode();
            }
            start();
        }
    }

    /** Starts the operation on first execution (no existing checkpoint). */
    protected abstract void start();

    /**
     * Replays the operation from an existing checkpoint.
     *
     * @param existing the checkpointed operation state
     */
    protected abstract void replay(Operation existing);

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
     * Gets the direct child Operations of this context operation
     *
     * @return list of the child Operations
     */
    protected List<Operation> getChildOperations() {
        return executionManager.getChildOperations(getOperationId());
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

    /** Returns true if this operation has completed (successfully or exceptionally). */
    protected boolean isOperationCompleted() {
        return completionFuture.isDone();
    }

    /**
     * Waits for the operation to complete. Deregisters the current thread to allow Lambda suspension if the operation
     * is still in progress, then re-registers when the operation completes.
     *
     * @return the completed operation
     */
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
                executionManager.deregisterActiveThread(threadContext.threadId());
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

    /**
     * Receives operation updates from ExecutionManager. Completes the internal future when the operation reaches a
     * terminal status, unblocking any threads waiting on this operation.
     *
     * @param operation the updated operation state
     */
    public void onCheckpointComplete(Operation operation) {
        if (ExecutionManager.isTerminalStatus(operation.status())) {
            // This method handles only terminal status updates. Override this method if a DurableOperation needs to
            // handle other updates.
            logger.trace("In onCheckpointComplete, completing operation {} ({})", getOperationId(), completionFuture);
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
        logger.trace("In markAlreadyCompleted, completing operation: {} ({}).", getOperationId(), completionFuture);

        // It's important that we synchronize access to the future, otherwise the processing could happen
        // on someone else's thread and cause a race condition.
        synchronized (completionFuture) {
            completionFuture.complete(null);
        }
    }

    /**
     * Terminates the execution with the given exception.
     *
     * @param exception the unrecoverable exception
     * @return never returns normally; always throws
     */
    protected T terminateExecution(UnrecoverableDurableExecutionException exception) {
        executionManager.terminateExecution(exception);
        // Exception is already thrown from above. Keep the throw statement below to make tests happy
        throw exception;
    }

    /**
     * Terminates the execution with an {@link IllegalDurableOperationException}.
     *
     * @param message the error message
     * @return never returns normally; always throws
     */
    protected T terminateExecutionWithIllegalDurableOperationException(String message) {
        return terminateExecution(new IllegalDurableOperationException(message));
    }

    /**
     * Registers a thread as active in the execution manager.
     *
     * @param threadId the thread identifier to register
     */
    protected void registerActiveThread(String threadId) {
        executionManager.registerActiveThread(threadId);
    }

    /** Returns the current thread's context from the execution manager. */
    protected ThreadContext getCurrentThreadContext() {
        return executionManager.getCurrentThreadContext();
    }

    /** Polls the backend for updates to this operation. */
    protected CompletableFuture<Operation> pollForOperationUpdates() {
        return executionManager.pollForOperationUpdates(getOperationId());
    }

    /**
     * Polls the backend for updates to this operation after the specified delay.
     *
     * @param delay the delay before polling
     * @return a future that completes with the updated operation
     */
    protected CompletableFuture<Operation> pollForOperationUpdates(Duration delay) {
        return executionManager.pollForOperationUpdates(getOperationId(), delay);
    }

    /** Sends an operation update synchronously (blocks until the update is acknowledged). */
    protected void sendOperationUpdate(OperationUpdate.Builder builder) {
        sendOperationUpdateAsync(builder).join();
    }

    /** Sends an operation update asynchronously. */
    protected CompletableFuture<Void> sendOperationUpdateAsync(OperationUpdate.Builder builder) {
        var updateBuilder =
                builder.id(getOperationId()).name(getName()).type(getType()).parentId(durableContext.getContextId());
        if (getSubType() != null) {
            updateBuilder.subType(getSubType().getValue());
        }
        return executionManager.sendOperationUpdate(updateBuilder.build());
    }

    /**
     * Deserializes a result string into the operation's result type.
     *
     * @param result the serialized result string
     * @return the deserialized result
     * @throws SerDesException if deserialization fails
     */
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

    /**
     * Serializes the result to a string.
     *
     * @param result the result to serialize
     * @return the serialized string
     */
    protected String serializeResult(T result) {
        return resultSerDes.serialize(result);
    }

    /**
     * Serializes a throwable into an {@link ErrorObject} for checkpointing.
     *
     * @param throwable the exception to serialize
     * @return the serialized error object
     */
    protected ErrorObject serializeException(Throwable throwable) {
        return ExceptionHelper.buildErrorObject(throwable, resultSerDes);
    }

    /**
     * Deserializes an {@link ErrorObject} back into a throwable, reconstructing the original exception type and stack
     * trace when possible. Falls back to null if the exception class is not found or deserialization fails.
     *
     * @param errorObject the serialized error object
     * @return the reconstructed throwable, or null if reconstruction is not possible
     */
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
                    getOperationId(), checkpointed.type(), getType())));
        }

        if (!Objects.equals(checkpointed.name(), getName())) {
            terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation name mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                    getOperationId(), checkpointed.name(), getName())));
        }

        if ((getSubType() == null && checkpointed.subType() != null)
                || getSubType() != null
                        && !Objects.equals(checkpointed.subType(), getSubType().getValue())) {
            terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation subType mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                    getOperationId(), checkpointed.subType(), getSubType())));
        }
    }
}
