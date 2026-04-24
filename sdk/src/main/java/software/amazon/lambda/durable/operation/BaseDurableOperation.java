// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
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
public abstract class BaseDurableOperation {
    private static final Logger logger = LoggerFactory.getLogger(BaseDurableOperation.class);

    private final OperationIdentifier operationIdentifier;
    protected final ExecutionManager executionManager;
    protected final CompletableFuture<BaseDurableOperation> completionFuture;
    protected final BaseDurableOperation parentOperation;
    protected final boolean isVirtual;
    private final DurableContextImpl durableContext;
    private final AtomicReference<CompletableFuture<Void>> runningUserHandler = new AtomicReference<>(null);
    private final AtomicBoolean replayCompletedOperation = new AtomicBoolean(false);

    protected BaseDurableOperation(
            OperationIdentifier operationIdentifier,
            DurableContextImpl durableContext,
            BaseDurableOperation parentOperation) {
        this(operationIdentifier, durableContext, parentOperation, false);
    }

    /**
     * Constructs a new durable operation.
     *
     * @param operationIdentifier the unique identifier for this operation
     * @param durableContext the parent context this operation belongs to
     * @param parentOperation the parent operation if this is a branch/iteration of a ConcurrencyOperation
     */
    protected BaseDurableOperation(
            OperationIdentifier operationIdentifier,
            DurableContextImpl durableContext,
            BaseDurableOperation parentOperation,
            boolean isVirtual) {
        this.operationIdentifier = operationIdentifier;
        this.parentOperation = parentOperation;
        this.durableContext = durableContext;
        this.executionManager = durableContext.getExecutionManager();
        this.isVirtual = isVirtual;

        this.completionFuture = new CompletableFuture<>();

        // register this operation in ExecutionManager so that the operation can receive updates from ExecutionManager
        executionManager.registerOperation(this);
    }

    public CompletableFuture<BaseDurableOperation> getCompletionFuture() {
        return completionFuture;
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
        if (isVirtual) {
            // We never persist virtual operations, so always call start
            start();
        } else {
            var existing = getOperation();

            if (existing != null) {
                validateReplay(existing);
                if (ExecutionManager.isTerminalStatus(existing.status())) {
                    replayCompletedOperation.set(true);
                }
                replay(existing);
            } else {
                if (durableContext.isReplaying()) {
                    this.durableContext.setExecutionMode();
                }
                start();
            }
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
            throw terminateExecutionWithIllegalDurableOperationException(message);
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
        CompletableFuture<?> future = completionFuture;

        // It's important that we synchronize access to the future. Otherwise, a race condition could happen if the
        // completionFuture is completed by a user thread (a step or child context thread) when the execution here
        // is between `isOperationCompleted` and `thenRun`.
        // If this operation is a branch/iteration of a ConcurrencyOperation (map or parallel), the branches/iterations
        // must be completed sequentially to avoid race conditions.
        synchronized (parentOperation == null ? completionFuture : parentOperation.completionFuture) {
            if (!isOperationCompleted()) {
                // Add a completion stage to completionFuture so that when the completionFuture is completed,
                // it will register the current Context thread synchronously to make sure it is always registered
                // strictly before the execution thread (Step or child context) is deregistered.
                // chain them together
                future = completionFuture.thenRun(() -> registerActiveThread(threadContext.threadId()));

                // Deregister the current thread to allow suspension
                deregisterActiveThread(threadContext.threadId());
            }
        }

        // Block until operation completes. No-op if the future is already completed.
        try {
            future.join();
        } catch (Throwable throwable) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(throwable));
        }

        if (isVirtual) {
            // We don't  store virtual operations so they don't corresponding Operation in storage
            return null;
        } else {
            // Get result based on status
            var op = getOperation();
            if (op == null) {
                throw terminateExecutionWithIllegalDurableOperationException(
                        String.format("%s operation not found: %s", getType(), getOperationId()));
            }
            return op;
        }
    }

    protected void runUserHandler(Runnable runnable, ThreadType threadType) {
        String operationId = getOperationId();
        logger.debug("Starting user handler for operation {} ({})", operationId, threadType);
        Runnable wrapped = () -> {
            executionManager.setCurrentThreadContext(new ThreadContext(operationId, threadType));
            try {
                runnable.run();
            } catch (Throwable throwable) {
                // Operations always wrap the user's function and handles all possible exceptions except for
                // SuspendExecutionException.
                if (!executionManager.isExecutionCompletedExceptionally()
                        && !(throwable instanceof SuspendExecutionException)) {
                    logger.error("An unhandled exception is thrown from user function: ", throwable);
                    throw terminateExecutionWithIllegalDurableOperationException(
                            "An unhandled exception is thrown from user function: " + throwable);
                }
            } finally {
                if (operationId != null) {
                    try {
                        logger.trace("deregistering thread {} after running user handler {}", operationId, getName());
                        // if this is a child context or a step context, we need to
                        // deregister the context's thread from the execution manager
                        deregisterActiveThread(operationId);
                    } catch (SuspendExecutionException e) {
                        // Expected when this is the last active thread. Must catch here because:
                        // 1/ This runs in a worker thread detached from handlerFuture
                        // 2/ Uncaught exception would prevent stepAsync().get() from resume
                        // Suspension/Termination is already signaled via
                        // suspendExecutionFuture/terminateExecutionFuture
                        // before the throw.
                    }
                }
            }
        };

        // runUserHandler is used to ensure that only one user handler is running at a time
        if (runningUserHandler.get() != null && !runningUserHandler.get().isDone()) {
            logger.error("User handler already running for operation {} ({})", getOperationId(), threadType);
            throw terminateExecutionWithIllegalDurableOperationException(
                    "User handler already running: " + getOperationId());
        }

        // Thread registration is intentionally split across two threads:
        // 1. registerActiveThread on the PARENT thread — ensures the child is tracked before the
        //    parent can deregister and trigger suspension (race prevention).
        // 2. setCurrentContext on the CHILD thread — sets the ThreadLocal so operations inside
        //    the child context know which context they belong to.
        // registerActiveThread is idempotent (no-op if already registered).
        registerActiveThread(operationId);

        runningUserHandler.set(CompletableFuture.runAsync(
                wrapped, getContext().getDurableConfig().getExecutorService()));
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

            markCompletionFutureCompleted();
        }
    }

    /** Marks the operation as already completed (in replay). */
    protected void markAlreadyCompleted() {
        // When the operation is already completed in a replay, we complete completionFuture immediately
        // so that the `get` method will be unblocked and the context thread will be registered
        logger.trace("In markAlreadyCompleted, completing operation: {} ({}).", getOperationId(), completionFuture);
        markCompletionFutureCompleted();
    }

    private void markCompletionFutureCompleted() {
        // It's important that we synchronize access to the future, otherwise the processing could happen
        // on someone else's thread and cause a race condition.
        synchronized (parentOperation == null ? completionFuture : parentOperation.completionFuture) {
            // Completing the future here will also run any other completion stages that have been attached
            // to the future. In our case, other contexts may have attached a function to reactivate themselves,
            // so they will definitely have a chance to reactivate before we finish completing and deactivating
            // whatever operations were just checkpointed.
            completionFuture.complete(this);
        }
    }

    /**
     * Terminates the execution with the given exception.
     *
     * @param exception the unrecoverable exception
     * @return never returns normally; always throws
     */
    protected RuntimeException terminateExecution(UnrecoverableDurableExecutionException exception) {
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
    protected RuntimeException terminateExecutionWithIllegalDurableOperationException(String message) {
        return terminateExecution(new IllegalDurableOperationException(message));
    }

    /**
     * Registers a thread as active in the execution manager.
     *
     * @param threadId the thread identifier to register
     */
    protected void registerActiveThread(String threadId) {
        logger.trace(
                "registering thread {} when operation {} ({}) completed ({})",
                threadId,
                getOperation(),
                getType(),
                completionFuture);
        executionManager.registerActiveThread(threadId);
    }

    protected void deregisterActiveThread(String threadId) {
        // Operation not done yet
        logger.trace(
                "deregistering thread {} when waiting for operation {} ({}) to complete ({})",
                getCurrentThreadContext().threadId(),
                getOperation(),
                getType(),
                completionFuture);

        executionManager.deregisterActiveThread(threadId);
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
     * Polls the backend for updates to this operation at a specific time.
     *
     * @param at the time to poll for updates
     * @return a future that completes with the updated operation
     */
    protected CompletableFuture<Operation> pollForOperationUpdates(Instant at) {
        return executionManager.pollForOperationUpdates(getOperationId(), at);
    }

    /** Sends an operation update synchronously (blocks until the update is acknowledged). */
    protected void sendOperationUpdate(OperationUpdate.Builder builder) {
        sendOperationUpdateAsync(builder).join();
    }

    /** Sends an operation update asynchronously. */
    protected CompletableFuture<Void> sendOperationUpdateAsync(OperationUpdate.Builder builder) {
        var updateBuilder =
                builder.id(getOperationId()).name(getName()).type(getType()).parentId(durableContext.getParentId());
        if (getSubType() != null) {
            updateBuilder.subType(getSubType().getValue());
        }
        var update = updateBuilder.build();
        if (replayCompletedOperation.get()) {
            // We are replaying a completed operation, so complete the completableFuture without checkpointing
            logger.debug("Skipping send operation update for replay completed operation: {}", getOperationId());
            onCheckpointComplete(getOperation());
            return CompletableFuture.completedFuture(null);
        } else {
            return executionManager.sendOperationUpdate(update);
        }
    }

    /** Validates that current operation matches checkpointed operation during replay. */
    protected void validateReplay(Operation checkpointed) {
        if (checkpointed == null || checkpointed.type() == null) {
            return; // First execution, no validation needed
        }

        if (!checkpointed.type().equals(getType())) {
            throw terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation type mismatch for \"%s\". Expected %s, got %s",
                    getOperationId(), checkpointed.type(), getType())));
        }

        if (!Objects.equals(checkpointed.name(), getName())) {
            throw terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation name mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                    getOperationId(), checkpointed.name(), getName())));
        }

        if ((getSubType() == null && checkpointed.subType() != null)
                || getSubType() != null
                        && !Objects.equals(checkpointed.subType(), getSubType().getValue())) {
            throw terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation subType mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                    getOperationId(), checkpointed.subType(), getSubType())));
        }
    }

    public CompletableFuture<Void> getRunningUserHandler() {
        return runningUserHandler.get();
    }
}
