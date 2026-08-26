// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.SafeCloseable;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.PluginInfoConverter;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Central manager for durable execution coordination.
 *
 * <p>Consolidates:
 *
 * <ul>
 *   <li>Execution state (operations, checkpoint token)
 *   <li>Thread lifecycle (registration/deregistration)
 *   <li>Checkpoint batching (via CheckpointManager)
 *   <li>Checkpoint result handling (CheckpointManager callback)
 *   <li>Polling (for waits and retries)
 * </ul>
 *
 * <p>This is the single entry point for all execution coordination. Internal coordination (polling, checkpointing) uses
 * a dedicated SDK thread pool, while user-defined operations run on a customer-configured executor.
 *
 * <p>Operations are keyed by their globally unique operation ID. Child context operations use prefixed IDs (e.g.,
 * "1-1", "1-2") to avoid collisions with root-level operations.
 *
 * @see InternalExecutor
 */
public class ExecutionManager implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionManager.class);

    // ===== Execution State =====
    private final Map<String, Operation> operationStorage;
    private final Operation executionOp;
    private final String durableExecutionArn;
    private final Context lambdaContext;
    private final AtomicReference<ExecutionMode> executionMode;
    private final DurableConfig durableConfig;
    private final Set<String> updatedOperationIdsSinceLastInvocation;
    private final Set<String> initialOperationIds;

    // ===== Thread Coordination =====
    private final Map<String, BaseDurableOperation> registeredOperations = new ConcurrentHashMap<>();
    private final Set<String> activeThreads = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<ThreadContext> currentThreadContext = new ThreadLocal<>();
    private final CompletableFuture<Void> executionExceptionFuture = new CompletableFuture<>();

    enum FutureWaitState {
        ACTIVE,
        DEREGISTERING,
        WAITING,
        SUSPENDING,
        COMPLETED,
        SUSPENDED
    }

    // ===== Checkpoint Batching =====
    private final CheckpointManager checkpointManager;

    public ExecutionManager(DurableExecutionInput input, DurableConfig config, Context lambdaContext) {
        durableConfig = config;
        this.durableExecutionArn = input.durableExecutionArn();
        this.lambdaContext = lambdaContext;

        // Store the set of operation IDs updated since the last successful invocation
        this.updatedOperationIdsSinceLastInvocation =
                input.updatedOperationIds() != null ? Set.copyOf(input.updatedOperationIds()) : Collections.emptySet();

        // Create checkpoint batcher for internal coordination
        this.checkpointManager =
                new CheckpointManager(config, durableExecutionArn, input.checkpointToken(), this::onCheckpointComplete);

        this.operationStorage = checkpointManager.fetchAllPages(input.initialExecutionState()).stream()
                .collect(Collectors.toConcurrentMap(Operation::id, op -> op));

        // The ids delivered in this invocation's initial state. Everything else in operationStorage is created during
        // this invocation, so this set is what distinguishes replayed operations from freshly-started ones for the
        // plugin hooks' isReplay indicators.
        this.initialOperationIds = Set.copyOf(operationStorage.keySet());

        // Start in REPLAY mode if we have more than just the initial EXECUTION operation
        this.executionMode =
                new AtomicReference<>(operationStorage.size() > 1 ? ExecutionMode.REPLAY : ExecutionMode.EXECUTION);

        // parse durableExecutionArn and get the last part after / which is the invocation id
        var durableExecutionArnParts = durableExecutionArn.split("/", -1);
        var invocationId = durableExecutionArnParts[durableExecutionArnParts.length - 1];
        executionOp = operationStorage.get(invocationId);

        // Validate initial operation is an EXECUTION operation
        if (executionOp == null) {
            throw new IllegalStateException("EXECUTION operation not found");
        }
        logger.debug("DurableExecution.execute() called");
        logger.debug("DurableExecutionArn: {}", durableExecutionArn);
        logger.debug("Initial operations count: {}", operationStorage.size());
        logger.debug("EXECUTION operation found: {}", executionOp.id());
    }

    // ===== State Management =====

    /** Returns the ARN of the durable execution being managed. */
    public String getDurableExecutionArn() {
        return durableExecutionArn;
    }

    /** Returns {@code true} if the execution is currently replaying completed operations. */
    public boolean isReplaying() {
        return executionMode.get() == ExecutionMode.REPLAY;
    }

    /**
     * Returns {@code true} if the given operation was updated since the last successful invocation. This is used by the
     * OTel plugin to determine whether a replayed completed operation should emit a span — only operations that
     * transitioned during suspension should be traced on reinvocation.
     *
     * @param operationId the operation ID to check
     * @return true if the operation was updated since the last successful invocation
     */
    public boolean isOperationUpdatedSinceLastInvocation(String operationId) {
        return updatedOperationIdsSinceLastInvocation.contains(operationId);
    }

    /**
     * Returns {@code true} if the given operation was present in the checkpointed state delivered at the start of this
     * invocation, i.e. it predates this invocation and is being replayed rather than started fresh. Unlike
     * {@link #getOperationAndUpdateReplayState(String)} this does not mutate the execution's replay mode, so it is safe
     * to call from plugin-hook firing sites.
     *
     * @param operationId the operation ID to check
     * @return true if the operation was delivered in this invocation's initial state
     */
    public boolean wasObservedAtInvocationStart(String operationId) {
        return initialOperationIds.contains(operationId);
    }

    /** Returns the ids of the operations delivered in this invocation's initial state. */
    public Set<String> getInitialOperationIds() {
        return initialOperationIds;
    }

    /**
     * Returns an immutable snapshot of the operations currently tracked for this execution, including the initial
     * EXECUTION operation. Non-mutating; intended for the invocation-level plugin hooks.
     *
     * @return a snapshot of the tracked operations
     */
    public Collection<Operation> getOperationsSnapshot() {
        return List.copyOf(operationStorage.values());
    }

    /**
     * Returns the subset of {@link #getOperationsSnapshot()} whose ids the backend reported as updated since the last
     * successful invocation. Empty on the first invocation. Ids without a corresponding tracked operation are skipped.
     *
     * @return a snapshot of the externally-updated operations
     */
    public Collection<Operation> getUpdatedOperationsSnapshot() {
        return updatedOperationIdsSinceLastInvocation.stream()
                .map(operationStorage::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Registers an operation so it can receive checkpoint completion notifications. */
    public void registerOperation(BaseDurableOperation operation) {
        registeredOperations.put(operation.getOperationId(), operation);
    }

    // ===== Checkpoint Completion Handler =====
    /** Called by CheckpointManager when a checkpoint completes. Updates operationStorage and notify operations . */
    void onCheckpointComplete(List<Operation> newOperations) {
        var updatedOperations = new ArrayList<Operation>();
        newOperations.forEach(op -> {
            // Detect a status change against the previously stored operation
            var previous = operationStorage.get(op.id());
            if (previous == null || previous.status() != op.status()) {
                updatedOperations.add(op);
            }
            // Publish the updated state and notify its waiter atomically. Otherwise, a waiter can observe the terminal
            // state before its completion future is completed and attempt to suspend with no pending operations.
            var registeredOperation = registeredOperations.get(op.id());
            if (registeredOperation == null) {
                operationStorage.put(op.id(), op);
            } else {
                registeredOperation.processCheckpointUpdate(op, () -> operationStorage.put(op.id(), op));
            }
        });

        // Fire onOperationChange when a checkpoint response changed one or more operations
        if (!updatedOperations.isEmpty()) {
            var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
            durableConfig
                    .getPluginRunner()
                    .onOperationChange(PluginInfoConverter.toOperationChangeInfo(
                            requestId,
                            durableExecutionArn,
                            updatedOperations,
                            operationStorage.values(),
                            initialOperationIds));
        }
    }

    /**
     * Gets all child operations for a given operationId.
     *
     * @param operationId the operationId to get children for
     * @return List of child operations for the given operationId
     */
    public List<Operation> getChildOperations(String operationId) {
        // todo: this is O(n) - consider an improvement if performance becomes an issue
        var children = new ArrayList<Operation>();
        for (Operation op : operationStorage.values()) {
            if (Objects.equals(op.parentId(), operationId)) {
                children.add(op);
            }
        }
        return children;
    }

    /**
     * Gets an operation by its globally unique operationId, and updates replay state. Transitions from REPLAY to
     * EXECUTION mode if the operation is not found or is not in a terminal state (still in progress).
     *
     * @param operationId the globally unique operation ID (e.g., "1" for root, "1-1" for child context)
     * @return the existing operation, or null if not found (first execution)
     */
    public Operation getOperationAndUpdateReplayState(String operationId) {
        var existing = operationStorage.get(operationId);
        if (executionMode.get() == ExecutionMode.REPLAY && (existing == null || !isTerminalStatus(existing.status()))) {
            if (executionMode.compareAndSet(ExecutionMode.REPLAY, ExecutionMode.EXECUTION)) {
                logger.debug("Transitioned to EXECUTION mode at operation '{}'", operationId);
            }
        }
        return existing;
    }

    /** Returns the initial EXECUTION operation from the checkpoint state. */
    public Operation getExecutionOperation() {
        return executionOp;
    }

    /**
     * Checks whether there are any cached operations for the given parent context ID. Used to initialize per-context
     * replay state — a context starts in replay mode if the ExecutionManager has cached operations belonging to it.
     *
     * @param parentId the context ID to check (null for root context)
     * @return true if at least one operation exists with the given parentId
     */
    public boolean hasOperationsForContext(String parentId) {
        return operationStorage.values().stream()
                .anyMatch(op -> op.type() != OperationType.EXECUTION && Objects.equals(op.parentId(), parentId));
    }

    // ===== Thread Coordination =====
    /** Sets the current thread's ThreadContext (threadId and threadType). Called when a user thread is started. */
    public void setCurrentThreadContext(ThreadContext threadContext) {
        currentThreadContext.set(threadContext);
    }

    /** Returns the current thread's ThreadContext (threadId and threadType), or null if not set. */
    public ThreadContext getCurrentThreadContext() {
        return currentThreadContext.get();
    }

    /**
     * Waits for a future from the current durable context thread.
     *
     * <p>The current thread is marked inactive while waiting so the execution can suspend. It is reactivated
     * synchronously when the future completes.
     *
     * @param future the future to wait for
     * @param <T> the future result type
     * @return the completed result
     */
    public <T> T awaitFuture(CompletableFuture<T> future) {
        try {
            return deactivateCurrentThreadUntilComplete(future).join();
        } catch (Throwable throwable) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(throwable));
            return null;
        }
    }

    /**
     * Marks the current durable context thread inactive until the supplied stage completes.
     *
     * <p>No platform thread is blocked. The returned future completes only after the logical durable thread has been
     * reactivated, or exceptionally when the invocation suspends or terminates.
     *
     * @param stage the asynchronous work awaited by the current durable context
     * @param <T> the result type
     * @return a future that completes after durable-thread reactivation
     */
    public <T> CompletableFuture<T> deactivateCurrentThreadUntilComplete(CompletionStage<T> stage) {
        Objects.requireNonNull(stage, "stage cannot be null");
        var source = copyStage(stage);
        var threadContext = getCurrentThreadContext();
        if (threadContext == null || source.isDone()) {
            return source;
        }

        var waitState = new AtomicReference<>(FutureWaitState.ACTIVE);
        var result = new CompletableFuture<T>();
        source.whenComplete((value, throwable) -> {
            completeFutureWait(threadContext.threadId(), waitState);
            completeResult(result, value, throwable);
        });
        executionExceptionFuture.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(ExceptionHelper.unwrapCompletableFuture(throwable));
            }
        });

        if (waitState.compareAndSet(FutureWaitState.ACTIVE, FutureWaitState.DEREGISTERING)) {
            deregisterActiveThreadForFuture(threadContext.threadId(), waitState);
        }
        return result;
    }

    private void completeFutureWait(String threadId, AtomicReference<FutureWaitState> waitState) {
        while (true) {
            var current = waitState.get();
            if (current == FutureWaitState.COMPLETED || current == FutureWaitState.SUSPENDED) {
                return;
            }
            if (waitState.compareAndSet(current, FutureWaitState.COMPLETED)) {
                if (current == FutureWaitState.WAITING || current == FutureWaitState.SUSPENDING) {
                    registerActiveThreadIfRunning(threadId);
                }
                return;
            }
        }
    }

    void deregisterActiveThreadForFuture(String threadId, AtomicReference<FutureWaitState> waitState) {
        var shouldSuspend = false;
        synchronized (activeThreads) {
            removeActiveThread(threadId);
            if (!waitState.compareAndSet(FutureWaitState.DEREGISTERING, FutureWaitState.WAITING)) {
                if (waitState.get() == FutureWaitState.COMPLETED) {
                    registerActiveThreadIfRunning(threadId);
                }
                return;
            }
            if (activeThreads.isEmpty()) {
                shouldSuspend = waitState.compareAndSet(FutureWaitState.WAITING, FutureWaitState.SUSPENDING);
            }
        }
        if (shouldSuspend) {
            synchronized (activeThreads) {
                if (activeThreads.isEmpty()) {
                    if (waitState.compareAndSet(FutureWaitState.SUSPENDING, FutureWaitState.SUSPENDED)) {
                        signalSuspendForNoActiveThreads();
                    }
                } else {
                    waitState.compareAndSet(FutureWaitState.SUSPENDING, FutureWaitState.WAITING);
                }
            }
        }
    }

    private void registerActiveThreadIfRunning(String threadId) {
        synchronized (activeThreads) {
            if (!isExecutionCompletedExceptionally()) {
                registerActiveThread(threadId);
            }
        }
    }

    /**
     * Registers a thread as active.
     *
     * @see ThreadContext
     */
    public void registerActiveThread(String threadId) {
        synchronized (activeThreads) {
            if (activeThreads.add(threadId)) {
                logger.trace("Registered thread '{}' as active. Active threads: {}", threadId, activeThreads.size());
            } else {
                logger.warn("Thread '{}' already registered as active", threadId);
            }
        }
    }

    /**
     * Mark a thread as inactive. If no threads remain, suspends the execution.
     *
     * @param threadId the thread ID to deregister
     */
    public void deregisterActiveThread(String threadId) {
        // Skip if already suspended
        if (executionExceptionFuture.isDone()) {
            return;
        }

        // Add synchronized block to avoid remove then check race condition and make sure that
        // the suspendExecution is called only once
        synchronized (activeThreads) {
            removeActiveThread(threadId);
            if (activeThreads.isEmpty()) {
                suspendForNoActiveThreads();
            }
        }
    }

    private void removeActiveThread(String threadId) {
        if (activeThreads.remove(threadId)) {
            logger.trace("Deregistered thread '{}' Active threads: {}", threadId, activeThreads.size());
        } else {
            logger.warn("Thread '{}' not active, cannot deregister", threadId);
        }
    }

    private void suspendForNoActiveThreads() {
        var exception = signalSuspendForNoActiveThreads();
        throw exception;
    }

    private SuspendExecutionException signalSuspendForNoActiveThreads() {
        logger.info("No active threads remaining - suspending execution");
        preSuspendCheck();
        return signalSuspendExecution();
    }

    private void preSuspendCheck() {
        var hasAnyPendingOperation = operationStorage.values().stream().anyMatch(o -> switch (o.type()) {
            case STEP -> o.status() == OperationStatus.PENDING;
            case WAIT, CALLBACK -> o.status() == OperationStatus.STARTED;
            case CHAINED_INVOKE -> o.status() == OperationStatus.PENDING || o.status() == OperationStatus.STARTED;
            default -> false;
        });

        if (!hasAnyPendingOperation) {
            logger.warn("Invalid suspension. No operation is pending");
        }
    }

    // ===== Checkpointing =====

    // This method will checkpoint the operation updates to the durable backend and return a future which completes
    // when the checkpoint completes.
    public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
        return checkpointManager.checkpoint(update);
    }

    // ===== Polling =====

    // This method will poll the operation updates from the durable backend and return a future which completes
    // when an update of the operation is received.
    // This is useful for in-process waits. For example, we want to
    // wait while another thread is still running, and we therefore are not
    // re-invoked because we never suspended.
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId) {
        return checkpointManager.pollForUpdate(operationId);
    }

    /**
     * Pools for operation updates at a specific time
     *
     * @param operationId the operation id to poll for updates
     * @param at the time to poll for updates
     * @return a completable future that completes with the operation update
     */
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId, Instant at) {
        return checkpointManager.pollForUpdate(operationId, at);
    }

    // ===== Utilities =====
    /** Shutdown the checkpoint batcher. */
    @Override
    public void close() {
        validateRunningThreads();

        checkpointManager.shutdown();
    }

    private void validateRunningThreads() {
        // This will detect stuck user thread and thread leaks in the thread pool
        for (BaseDurableOperation op : registeredOperations.values()) {
            var userHandlerFuture = op.getRunningUserHandler();
            if (userHandlerFuture != null && !userHandlerFuture.isDone()) {
                // Some user threads can still be running because
                // the operations that run them have never been waiting for and the execution has completed.
                logger.info("Waiting for operation to complete before shutting down: {}", op.getOperationId());
                try {
                    userHandlerFuture.get();
                } catch (InterruptedException | CancellationException e) {
                    // if the user handler is stuck
                    throw new IllegalStateException(
                            "Stuck running user handler when shutting down: " + op.getOperationId());
                } catch (Exception e) {
                    // ok if the future completed exceptionally
                }
            }
        }

        // double check if the thread pool is empty
        if (durableConfig.getExecutorService() instanceof ThreadPoolExecutor threadPoolExecutor) {
            var threadCount = threadPoolExecutor.getActiveCount();
            // This may or may not be a problem because getActiveCount doesn't return an accurate number
            if (threadCount > 0) {
                logger.warn("{} active threads in user executor pool when shutting down", threadCount);
            }
        }
    }

    /** Returns {@code true} if the given status represents a terminal (final) operation state. */
    public static boolean isTerminalStatus(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED
                || status == OperationStatus.FAILED
                || status == OperationStatus.CANCELLED
                || status == OperationStatus.TIMED_OUT
                || status == OperationStatus.STOPPED;
    }

    /**
     * Terminates the execution immediately with an unrecoverable error.
     *
     * @param exception the unrecoverable exception that caused termination
     */
    public void terminateExecution(UnrecoverableDurableExecutionException exception) {
        stopAllOperations(exception);
        executionExceptionFuture.completeExceptionally(exception);
        throw exception;
    }

    /** Suspends the execution by completing the execution exception future with a {@link SuspendExecutionException}. */
    public void suspendExecution() {
        throw signalSuspendExecution();
    }

    private SuspendExecutionException signalSuspendExecution() {
        var exception = new SuspendExecutionException();
        stopAllOperations(exception);
        executionExceptionFuture.completeExceptionally(exception);
        return exception;
    }

    /**
     * returns {@code true} if the execution is terminated exceptionally (with a {@link SuspendExecutionException} or an
     * unrecoverable error).
     */
    public boolean isExecutionCompletedExceptionally() {
        return executionExceptionFuture.isCompletedExceptionally();
    }

    private void stopAllOperations(Throwable cause) {
        registeredOperations.values().forEach(op -> op.getCompletionFuture().completeExceptionally(cause));
    }

    /**
     * return a future that completes when userFuture completes successfully or the execution is terminated or
     * suspended.
     *
     * @param userFuture user provided function
     * @return a future of userFuture result if userFuture completes successfully, a user exception if userFuture
     *     completes with an exception, a SuspendExecutionException if the execution is suspended, or an
     *     UnrecoverableDurableExecutionException if the execution is terminated.
     */
    public <T> CompletableFuture<T> runUntilCompleteOrSuspend(CompletableFuture<T> userFuture) {
        return CompletableFuture.anyOf(userFuture, executionExceptionFuture).thenApply(v -> {
            // reaches here only if userFuture complete successfully
            if (userFuture.isDone()) {
                return userFuture.join();
            }
            return null;
        });
    }

    private static <T> CompletableFuture<T> copyStage(CompletionStage<T> stage) {
        var result = new CompletableFuture<T>();
        stage.whenComplete((value, throwable) -> completeResult(result, value, throwable));
        return result;
    }

    private static <T> void completeResult(CompletableFuture<T> result, T value, Throwable throwable) {
        if (throwable == null) {
            result.complete(value);
        } else {
            result.completeExceptionally(ExceptionHelper.unwrapCompletableFuture(throwable));
        }
    }
}
