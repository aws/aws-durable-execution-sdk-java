// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.plugin.PluginInfoConverter;
import software.amazon.lambda.durable.plugin.PluginRunner;
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

    private final PrimitiveOperationIdentifier operationIdentifier;
    protected final ExecutionManager executionManager;
    protected final CompletableFuture<BaseDurableOperation> completionFuture;
    protected final BaseDurableOperation parentOperation;
    protected final boolean isVirtual;
    protected final AtomicBoolean replayCompletedOperation = new AtomicBoolean(false);
    private final DurableContextImpl durableContext;
    private final AtomicReference<CompletableFuture<Void>> runningUserHandler = new AtomicReference<>(null);

    protected BaseDurableOperation(
            OperationIdentifier operationIdentifier,
            DurableContextImpl durableContext,
            BaseDurableOperation parentOperation) {
        this(PrimitiveOperationIdentifier.from(operationIdentifier), durableContext, parentOperation);
    }

    protected BaseDurableOperation(
            PrimitiveOperationIdentifier operationIdentifier,
            DurableContextImpl durableContext,
            BaseDurableOperation parentOperation) {
        this(operationIdentifier, durableContext, parentOperation, false);
    }

    /**
     * Constructs a new durable operation.
     *
     * @param operationIdentifier the unique identifier for this operation
     * @param durableContext the parent context this operation belongs to
     * @param parentOperation the operation that owns late-checkpoint suppression, if any
     * @param isVirtual whether this is a virtual operation that should not be persisted
     */
    protected BaseDurableOperation(
            OperationIdentifier operationIdentifier,
            DurableContextImpl durableContext,
            BaseDurableOperation parentOperation,
            boolean isVirtual) {
        this(PrimitiveOperationIdentifier.from(operationIdentifier), durableContext, parentOperation, isVirtual);
    }

    protected BaseDurableOperation(
            PrimitiveOperationIdentifier operationIdentifier,
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

    /**
     * Returns a non-mutating completion signal for public {@code DurableFuture} combinators.
     *
     * @return a future that completes with this operation
     */
    public CompletableFuture<Void> completionFuture() {
        return completionFuture.thenApply(ignored -> null);
    }

    /** Gets the operation sub-type (e.g. RUN_IN_CHILD_CONTEXT, WAIT_FOR_CALLBACK). */
    public OperationSubType getSubType() {
        return operationIdentifier.standardSubType();
    }

    /** Gets the exact operation subtype string. */
    public String getSubTypeValue() {
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
            // Virtual operations are not checkpointed, but we still fire plugin hooks
            // so the OTel plugin can emit spans for map/parallel iterations.
            fireOnOperationStart(null);
            start();
        } else {
            var existing = getOperation();

            if (existing != null) {
                validateReplay(existing);
                if (ExecutionManager.isTerminalStatus(existing.status())) {
                    replayCompletedOperation.set(true);
                } else {
                    // Non-terminal operations are being replayed. Fire onOperationStart so plugins
                    // can observe all in-progress operations during replay, including WAIT/INVOKE/CALLBACK
                    // that are still pending.
                    fireOnOperationStart(existing);
                }

                // Fire onOperationEnd for operations that completed during suspension (between invocations).
                // The OTel plugin handles the missing onOperationStart by creating a continuation span linked
                // to the deterministic span ID from the original invocation.
                if (replayCompletedOperation.get()
                        && executionManager.isOperationUpdatedSinceLastInvocation(getOperationId())) {
                    fireOnOperationEnd(existing, extractErrorFromOperation(existing), true);
                }

                replay(existing);
            } else {
                if (durableContext.isReplaying()) {
                    this.durableContext.setExecutionMode();
                }
                // Fire onOperationStart plugin hook (first execution, no existing operation)
                fireOnOperationStart(null);
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
     * @throws IllegalStateException if it's in a step
     */
    private void validateCurrentThreadType() {
        var threadContext = getCurrentThreadContext();
        if (threadContext != null && threadContext.threadType() == ThreadType.STEP) {
            var message = String.format(
                    "Nested %s operation is not supported on %s from within a %s execution.",
                    getType(), getName(), threadContext.threadType());
            throw new IllegalStateException(message);
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
        // Operations sharing a late-checkpoint owner must complete sequentially to avoid races with parent completion.
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

    /**
     * Runs an operation's handler on a user-executor thread, managing thread registration and suspension.
     *
     * <p>This method is responsible only for thread/executor/suspension scaffolding. Plugin user-function hooks are
     * fired by {@link #runUserFunction(Integer, java.util.function.Supplier)}, which operations wrap around the actual
     * user function so a user failure is reported through the hook boundary.
     *
     * @param runnable the operation handler to run (typically wraps a {@link #runUserFunction} call)
     * @param threadType the thread type (STEP or CONTEXT)
     */
    protected void runUserHandler(Runnable runnable, ThreadType threadType) {
        runUserHandlerAsync(
                () -> {
                    runnable.run();
                    return CompletableFuture.completedFuture(null);
                },
                (ignored, throwable) -> throwable == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(throwable),
                threadType,
                false);
    }

    /**
     * Runs an asynchronous operation user function without retaining a platform thread while its stage is incomplete.
     *
     * <p>When {@code deactivateWhileWaiting} is true, the logical durable thread is marked inactive after the callback
     * returns an incomplete stage and is reactivated before {@code completionHandler} runs. This is appropriate for
     * durable handler and child-context code that awaits other durable operations. Step bodies remain active while
     * asynchronous side effects are in flight.
     */
    protected <T> void runUserHandlerAsync(
            Supplier<? extends CompletionStage<T>> userFunction,
            BiFunction<T, Throwable, ? extends CompletionStage<Void>> completionHandler,
            ThreadType threadType,
            boolean deactivateWhileWaiting) {
        String operationId = getOperationId();
        logger.debug("Starting user handler for operation {} ({})", operationId, threadType);
        Objects.requireNonNull(userFunction, "userFunction cannot be null");
        Objects.requireNonNull(completionHandler, "completionHandler cannot be null");

        if (runningUserHandler.get() != null && !runningUserHandler.get().isDone()) {
            logger.error("User handler already running for operation {} ({})", getOperationId(), threadType);
            throw terminateExecutionWithIllegalDurableOperationException(
                    "User handler already running: " + getOperationId());
        }

        registerActiveThread(operationId);
        var lifecycle = new CompletableFuture<Void>();
        runningUserHandler.set(lifecycle);
        CompletableFuture.runAsync(
                        () -> startAsyncUserHandler(
                                userFunction, completionHandler, threadType, deactivateWhileWaiting, lifecycle),
                        getContext().getDurableConfig().getExecutorService())
                .exceptionally(throwable -> {
                    finishAsyncUserHandler(threadType, lifecycle, throwable);
                    return null;
                });
    }

    private <T> void startAsyncUserHandler(
            Supplier<? extends CompletionStage<T>> userFunction,
            BiFunction<T, Throwable, ? extends CompletionStage<Void>> completionHandler,
            ThreadType threadType,
            boolean deactivateWhileWaiting,
            CompletableFuture<Void> lifecycle) {
        executionManager.setCurrentThreadContext(new ThreadContext(getOperationId(), threadType));
        CompletionStage<T> userStage;
        try {
            userStage = Objects.requireNonNull(userFunction.get(), "User function stage cannot be null");
        } catch (Throwable throwable) {
            userStage = CompletableFuture.failedFuture(throwable);
        }

        var userFuture = copyStage(userStage);
        if (deactivateWhileWaiting && !userFuture.isDone()) {
            userFuture = executionManager.deactivateCurrentThreadUntilComplete(userFuture);
        }

        userFuture
                .handle(AsyncUserFunctionResult<T>::new)
                .thenComposeAsync(
                        result -> runAsyncCompletionHandler(result, completionHandler, threadType),
                        getContext().getDurableConfig().getExecutorService())
                .whenCompleteAsync(
                        (ignored, throwable) -> finishAsyncUserHandler(threadType, lifecycle, throwable),
                        getContext().getDurableConfig().getExecutorService());
    }

    private <T> CompletionStage<Void> runAsyncCompletionHandler(
            AsyncUserFunctionResult<T> result,
            BiFunction<T, Throwable, ? extends CompletionStage<Void>> completionHandler,
            ThreadType threadType) {
        executionManager.setCurrentThreadContext(new ThreadContext(getOperationId(), threadType));
        try {
            return Objects.requireNonNull(
                    completionHandler.apply(
                            result.value(),
                            result.throwable() == null
                                    ? null
                                    : ExceptionHelper.unwrapCompletableFuture(result.throwable())),
                    "Completion handler stage cannot be null");
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    private void finishAsyncUserHandler(ThreadType threadType, CompletableFuture<Void> lifecycle, Throwable throwable) {
        executionManager.setCurrentThreadContext(new ThreadContext(getOperationId(), threadType));
        var cause = throwable == null ? null : ExceptionHelper.unwrapCompletableFuture(throwable);
        if (cause != null
                && !executionManager.isExecutionCompletedExceptionally()
                && !(cause instanceof SuspendExecutionException)) {
            logger.error("An unhandled exception is thrown from user function: ", cause);
            try {
                terminateExecutionWithIllegalDurableOperationException(
                        "An unhandled exception is thrown from user function: " + cause);
            } catch (Throwable ignored) {
                // Termination is already recorded by the execution manager.
            }
        }
        try {
            logger.trace("deregistering thread {} after running user handler {}", getOperationId(), getName());
            deregisterActiveThread(getOperationId());
        } catch (SuspendExecutionException ignored) {
            // Suspension is already recorded by the execution manager.
        } finally {
            if (cause == null) {
                lifecycle.complete(null);
            } else {
                lifecycle.completeExceptionally(cause);
            }
        }
    }

    private static <T> CompletableFuture<T> copyStage(CompletionStage<T> stage) {
        var future = new CompletableFuture<T>();
        stage.whenComplete((value, throwable) -> {
            if (throwable == null) {
                future.complete(value);
            } else {
                future.completeExceptionally(ExceptionHelper.unwrapCompletableFuture(throwable));
            }
        });
        return future;
    }

    /**
     * Runs a user-provided function inside the plugin user-function hook boundary.
     *
     * <p>Fires {@code onUserFunctionStart} before invoking the function and {@code onUserFunctionEnd} after. The
     * function's exception propagates through this boundary so the end hook reports the true outcome, mirroring the
     * JS/Python SDKs. Retry and checkpoint handling stays in the caller's surrounding try/catch, outside this boundary.
     *
     * <p>{@code onUserFunctionEnd} fires for failures and suspensions alike so plugins (e.g. OTel) can end/clean up the
     * attempt rather than leak state; the original throwable is always re-thrown to the caller.
     *
     * @param attempt the 1-based attempt number for steps/waitForCondition, or null for context operations
     * @param userFunction the user function to invoke
     * @param <T> the user function's result type
     * @return the user function's result
     */
    protected <T> T runUserFunction(Integer attempt, Supplier<T> userFunction) {
        var pluginRunner = getPluginRunner();
        var startInfo = PluginInfoConverter.toUserFunctionStartInfo(
                operationIdentifier, durableContext.getParentId(), durableContext.isReplaying(), attempt);
        pluginRunner.onUserFunctionStart(startInfo);
        try {
            T result = userFunction.get();
            pluginRunner.onUserFunctionEnd(PluginInfoConverter.toUserFunctionEndInfo(startInfo, true, null));
            return result;
        } catch (Throwable e) {
            pluginRunner.onUserFunctionEnd(PluginInfoConverter.toUserFunctionEndInfo(startInfo, false, e));
            ExceptionHelper.sneakyThrow(e);
            return null; // unreachable — sneakyThrow always throws
        }
    }

    /** Runs an asynchronous user function inside the plugin user-function hook boundary. */
    protected <T> CompletionStage<T> runUserFunctionAsync(
            Integer attempt, Supplier<? extends CompletionStage<T>> userFunction) {
        var pluginRunner = getPluginRunner();
        var startInfo = PluginInfoConverter.toUserFunctionStartInfo(
                operationIdentifier, durableContext.getParentId(), durableContext.isReplaying(), attempt);
        pluginRunner.onUserFunctionStart(startInfo);

        final CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(userFunction.get(), "User function stage cannot be null");
        } catch (Throwable throwable) {
            pluginRunner.onUserFunctionEnd(PluginInfoConverter.toUserFunctionEndInfo(startInfo, false, throwable));
            return CompletableFuture.failedFuture(throwable);
        }
        var result = copyStage(stage);
        if (!result.isDone()) {
            pluginRunner.onUserFunctionAsyncReturn(startInfo);
        }

        return result.whenComplete((ignored, throwable) -> {
            var cause = throwable == null ? null : ExceptionHelper.unwrapCompletableFuture(throwable);
            pluginRunner.onUserFunctionEnd(PluginInfoConverter.toUserFunctionEndInfo(startInfo, cause == null, cause));
        });
    }

    private record AsyncUserFunctionResult<T>(T value, Throwable throwable) {}

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

            // Fire onOperationEnd plugin hook — operation reached terminal status for the first time (not replay)
            if (!replayCompletedOperation.get()) {
                fireOnOperationEnd(operation, extractErrorFromOperation(operation), false);
            }

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
        var updateBuilder = builder.id(getOperationId())
                .name(getName())
                .type(getType())
                .subType(getSubTypeValue())
                .parentId(durableContext.getParentId());
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

        if (!Objects.equals(checkpointed.subType(), getSubTypeValue())) {
            throw terminateExecution(new NonDeterministicExecutionException(String.format(
                    "Operation subType mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                    getOperationId(), checkpointed.subType(), getSubTypeValue())));
        }
    }

    public CompletableFuture<Void> getRunningUserHandler() {
        return runningUserHandler.get();
    }

    // ─── Plugin hook helpers ─────────────────────────────────────────────

    /** Returns the plugin runner from config, or no-op if config is unavailable. */
    private PluginRunner getPluginRunner() {
        var config = getContext().getDurableConfig();
        return config != null ? config.getPluginRunner() : PluginRunner.noOp();
    }

    /** Fires onOperationStart plugin hook. */
    private void fireOnOperationStart(Operation existing) {
        var info = PluginInfoConverter.toOperationInfo(existing, operationIdentifier, durableContext.getParentId());
        getPluginRunner().onOperationStart(info);
    }

    /** Fires onOperationEnd plugin hook when an operation reaches terminal status. */
    protected void fireOnOperationEnd(Operation operation, Throwable error, boolean isReplay) {
        var info = PluginInfoConverter.toOperationEndInfo(
                operation, operationIdentifier, durableContext.getParentId(), isReplay, error);
        getPluginRunner().onOperationEnd(info);
    }

    /**
     * Extracts the error from a terminal operation as a Throwable. Returns null if the operation succeeded or has no
     * error details.
     */
    public static Throwable extractErrorFromOperation(Operation operation) {
        if (operation.status() != OperationStatus.FAILED
                && operation.status() != OperationStatus.TIMED_OUT
                && operation.status() != OperationStatus.STOPPED) {
            return null;
        }
        var errorObject = getErrorObject(operation);
        if (errorObject == null) {
            return null;
        }
        return new DurableOperationException(operation, errorObject);
    }

    /** Extracts the ErrorObject from an operation based on its type. */
    public static ErrorObject getErrorObject(Operation operation) {
        if (operation.type() == null) {
            return null;
        }
        return switch (operation.type()) {
            case STEP ->
                operation.stepDetails() != null ? operation.stepDetails().error() : null;
            case CHAINED_INVOKE ->
                operation.chainedInvokeDetails() != null
                        ? operation.chainedInvokeDetails().error()
                        : null;
            case CALLBACK ->
                operation.callbackDetails() != null
                        ? operation.callbackDetails().error()
                        : null;
            case CONTEXT ->
                operation.contextDetails() != null ? operation.contextDetails().error() : null;
            default -> null;
        };
    }
}
