// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
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
 *   <li>Logical thread and executor-task lifecycle
 *   <li>Invocation deadline and work admission
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
    private static final AtomicLong LOCAL_INVOCATION_SEQUENCE = new AtomicLong();
    // Stop waiting for the root early enough to cancel tasks and flush checkpoints before the platform deadline.
    private static final Duration EXECUTION_DEADLINE_HEADROOM = Duration.ofMillis(500);
    // Leave a final margin for response serialization and return through the Lambda runtime wrapper.
    private static final Duration RESPONSE_HEADROOM = Duration.ofMillis(100);
    // Lambda always supplies a deadline; this cap also keeps local/null-context cleanup bounded.
    private static final Duration MAX_CLEANUP_DURATION = Duration.ofSeconds(30);
    private static final Duration MAX_GRACEFUL_DRAIN_DURATION = Duration.ofSeconds(5);
    private static final Duration MAX_CANCELLATION_DRAIN_DURATION = Duration.ofSeconds(1);
    private static final Duration CHECKPOINT_CLEANUP_RESERVE = Duration.ofMillis(100);
    private static final String LIFECYCLE_ERROR_TYPE =
            "software.amazon.lambda.durable.execution.InvocationLifecycleException";

    enum LifecycleState {
        /** The invocation accepts new durable operations, executor tasks, checkpoints, and polls. */
        OPEN,

        /**
         * Invocation shutdown has started. New operations and tasks are rejected, while already-admitted work may still
         * checkpoint or poll as it finishes.
         */
        DRAINING,

        /** Invocation cleanup has ended; all new operations, tasks, checkpoints, and polls are rejected. */
        CLOSED
    }

    record InvocationOutcome<T>(T result, Throwable failure) {}

    // ===== Execution State =====
    private final Map<String, Operation> operationStorage;
    private final Operation executionOp;
    private final String durableExecutionArn;
    private final Context lambdaContext;
    private final AtomicReference<ExecutionMode> executionMode;
    private final DurableConfig durableConfig;
    private final Set<String> updatedOperationIdsSinceLastInvocation;
    private final Set<String> initialOperationIds;

    // ===== Invocation Lifecycle =====
    private final Object admissionLock = new Object();
    private final String invocationId;
    private final Long deadlineNanos;
    private final AtomicLong taskSequence = new AtomicLong();
    private final Map<Long, ExecutorTaskHandle<?>> activeExecutorTasks = new ConcurrentHashMap<>();
    private volatile LifecycleState lifecycleState = LifecycleState.OPEN;
    private boolean checkpointAdmissionOpen = true;
    private boolean restoreInvocationThreadInterrupt;

    // ===== Thread Coordination =====
    private final Map<String, BaseDurableOperation> registeredOperations = new ConcurrentHashMap<>();
    private final Set<String> activeThreads = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<ThreadContext> currentThreadContext = new ThreadLocal<>();
    private final CompletableFuture<Void> executionExceptionFuture = new CompletableFuture<>();
    // Guarded by activeThreads so starting a checkpoint request is atomic with the last-thread suspension decision.
    private int checkpointRequestsInFlight;

    // ===== Checkpoint Batching =====
    private final CheckpointManager checkpointManager;

    public ExecutionManager(DurableExecutionInput input, DurableConfig config, Context lambdaContext) {
        durableConfig = config;
        this.durableExecutionArn = input.durableExecutionArn();
        this.lambdaContext = lambdaContext;
        this.invocationId = resolveInvocationId(lambdaContext, durableExecutionArn);
        this.deadlineNanos = resolveDeadlineNanos(lambdaContext);

        // Store the set of operation IDs updated since the last successful invocation
        this.updatedOperationIdsSinceLastInvocation =
                input.updatedOperationIds() != null ? Set.copyOf(input.updatedOperationIds()) : Collections.emptySet();

        // Create checkpoint batcher for internal coordination
        this.checkpointManager = new CheckpointManager(
                config,
                durableExecutionArn,
                input.checkpointToken(),
                this::onCheckpointComplete,
                this::tryStartCheckpointProcessing,
                this::finishCheckpointProcessing);

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
        synchronized (admissionLock) {
            requireOpen("durable operation");
            registeredOperations.put(operation.getOperationId(), operation);
        }
    }

    /** Submits the invocation's root handler and records its executor task separately from its logical result. */
    <T> CompletableFuture<T> submitRootTask(Supplier<T> action) {
        return submitExecutorTask(ExecutorTaskHandle.Role.ROOT, null, durableConfig.getExecutorService(), action);
    }

    /** Submits an operation handler and records its executor task separately from its logical result. */
    public CompletableFuture<Void> submitOperationTask(BaseDurableOperation operation, Runnable action) {
        var role = operation.getType() == OperationType.STEP
                ? ExecutorTaskHandle.Role.STEP
                : switch (operation.getSubType()) {
                    case MAP, PARALLEL -> ExecutorTaskHandle.Role.COORDINATOR;
                    default -> ExecutorTaskHandle.Role.CHILD_CONTEXT;
                };
        return submitExecutorTask(role, operation.getOperationId(), durableConfig.getExecutorService(), () -> {
            action.run();
            return null;
        });
    }

    private <T> CompletableFuture<T> submitExecutorTask(
            ExecutorTaskHandle.Role role, String operationId, ExecutorService executor, Supplier<T> action) {
        ExecutorTaskHandle<T> task;
        synchronized (admissionLock) {
            requireOpen("task");
            var taskId = taskSequence.incrementAndGet();
            task = new ExecutorTaskHandle<>(taskId, role, operationId, () -> activeExecutorTasks.remove(taskId));
            activeExecutorTasks.put(task.id(), task);
        }

        try {
            task.bindExecution(executor.submit(() -> task.run(action)));
            return task.completion();
        } catch (RuntimeException | Error failure) {
            task.submissionFailed(failure);
            throw failure;
        }
    }

    String getInvocationId() {
        return invocationId;
    }

    Optional<Duration> getRemainingInvocationTime() {
        if (deadlineNanos == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())));
    }

    LifecycleState getLifecycleState() {
        synchronized (admissionLock) {
            return lifecycleState;
        }
    }

    List<ExecutorTaskHandle<?>> getActiveExecutorTasks() {
        return activeExecutorTasks.values().stream()
                .sorted(Comparator.comparingLong(ExecutorTaskHandle::id))
                .toList();
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
            registeredOperations.compute(op.id(), (id, registeredOperation) -> {
                if (registeredOperation == null) {
                    operationStorage.put(op.id(), op);
                } else {
                    registeredOperation.processCheckpointUpdate(op, () -> operationStorage.put(op.id(), op));
                }
                return registeredOperation;
            });
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
        // Add synchronized block to avoid remove then check race condition and make sure that
        // the suspendExecution is called only once
        synchronized (activeThreads) {
            // Skip if already suspended
            if (executionExceptionFuture.isDone()) {
                return;
            }

            boolean removed = activeThreads.remove(threadId);
            if (removed) {
                logger.trace("Deregistered thread '{}' Active threads: {}", threadId, activeThreads.size());
            } else {
                logger.warn("Thread '{}' not active, cannot deregister", threadId);
            }

            if (shouldSuspendExecution()) {
                logger.info("No active threads remaining - suspending execution");
                preSuspendCheck();
                suspendExecution();
            }
        }
    }

    boolean tryStartCheckpointProcessing() {
        synchronized (activeThreads) {
            if (executionExceptionFuture.isDone() || lifecycleState == LifecycleState.CLOSED) {
                return false;
            }
            checkpointRequestsInFlight++;
            return true;
        }
    }

    void finishCheckpointProcessing() {
        synchronized (activeThreads) {
            if (checkpointRequestsInFlight == 0) {
                throw new IllegalStateException("No checkpoint request is in flight");
            }
            checkpointRequestsInFlight--;
            if (shouldSuspendExecution()) {
                logger.info("Checkpoint processing completed with no active threads - suspending execution");
                preSuspendCheck();
                signalSuspension();
            }
        }
    }

    private boolean shouldSuspendExecution() {
        return activeThreads.isEmpty() && checkpointRequestsInFlight == 0 && !executionExceptionFuture.isDone();
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
        return admitCheckpoint(() -> checkpointManager.checkpoint(update));
    }

    /** Waits for a response-critical checkpoint without crossing the invocation response deadline. */
    <T> T awaitCheckpointCompletion(CompletableFuture<T> checkpointFuture) {
        var waitNanos = deadlineNanos == null
                ? MAX_CLEANUP_DURATION.toNanos()
                : remainingNanos(deadlineNanos - RESPONSE_HEADROOM.toNanos());
        try {
            return checkpointFuture.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            restoreInvocationThreadInterrupt = true;
            throw lifecycleFailure("Interrupted while waiting for checkpoint completion", interrupted);
        } catch (TimeoutException timeout) {
            throw lifecycleFailure("Checkpoint completion exceeded the invocation deadline", timeout);
        } catch (ExecutionException failure) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(failure.getCause()));
            return null;
        }
    }

    // ===== Polling =====

    // This method will poll the operation updates from the durable backend and return a future which completes
    // when an update of the operation is received.
    // This is useful for in-process waits. For example, we want to
    // wait while another thread is still running, and we therefore are not
    // re-invoked because we never suspended.
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId) {
        return admitCheckpoint(() -> checkpointManager.pollForUpdate(operationId));
    }

    /**
     * Pools for operation updates at a specific time
     *
     * @param operationId the operation id to poll for updates
     * @param at the time to poll for updates
     * @return a completable future that completes with the operation update
     */
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId, Instant at) {
        return admitCheckpoint(() -> checkpointManager.pollForUpdate(operationId, at));
    }

    // ===== Invocation Cleanup =====
    @Override
    public void close() {
        var failure = finishInvocation(null);
        if (failure != null) {
            ExceptionHelper.sneakyThrow(failure);
        }
    }

    /** Drains invocation-owned tasks and shuts down checkpoint coordination before a response is returned. */
    Throwable finishInvocation(Throwable originalFailure) {
        if (lifecycleState == LifecycleState.CLOSED) {
            return originalFailure;
        }
        beginDraining();
        var hardDeadline = cleanupDeadlineNanos();
        Throwable cleanupFailure = null;
        try {
            cleanupFailure = drainExecutorTasks(originalFailure, hardDeadline);
        } catch (InterruptedException interrupted) {
            restoreInvocationThreadInterrupt = true;
            cleanupFailure = lifecycleFailure("Interrupted while draining invocation tasks", interrupted);
            signalLifecycleFailure(cleanupFailure);
            cancelActiveTasks();
        } finally {
            try {
                stopCheckpointAdmission();
                cleanupFailure = shutdownCheckpoints(hardDeadline, cleanupFailure);
            } finally {
                closeLifecycle();
                if (restoreInvocationThreadInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (cleanupFailure != null) {
            if (originalFailure != null && originalFailure != cleanupFailure) {
                cleanupFailure.addSuppressed(originalFailure);
            }
            return cleanupFailure;
        }
        return originalFailure;
    }

    private Throwable shutdownCheckpoints(long hardDeadline, Throwable priorFailure) {
        try {
            checkpointManager.shutdown(remainingDuration(hardDeadline));
            return priorFailure;
        } catch (InterruptedException interrupted) {
            restoreInvocationThreadInterrupt = true;
            return combineFailures(
                    priorFailure, lifecycleFailure("Interrupted while shutting down checkpoints", interrupted));
        } catch (ExecutionException | TimeoutException failure) {
            return combineFailures(
                    priorFailure, lifecycleFailure("Checkpoint shutdown exceeded its cleanup budget", failure));
        } catch (RuntimeException failure) {
            return combineFailures(priorFailure, lifecycleFailure("Checkpoint shutdown failed", failure));
        }
    }

    private Throwable drainExecutorTasks(Throwable originalFailure, long hardDeadline) throws InterruptedException {
        var taskDeadline = Math.max(System.nanoTime(), hardDeadline - CHECKPOINT_CLEANUP_RESERVE.toNanos());
        var retrying =
                originalFailure instanceof UnrecoverableDurableExecutionException failure && failure.isRetryable();
        if (!retrying) {
            var gracefulDeadline = minDeadline(taskDeadline, MAX_GRACEFUL_DRAIN_DURATION);
            if (awaitTasksUntil(gracefulDeadline)) {
                return null;
            }

            var failure = lifecycleFailure("Invocation tasks did not exit during graceful cleanup", null);
            signalLifecycleFailure(failure);
            cancelActiveTasks();
            awaitTasksUntil(minDeadline(taskDeadline, MAX_CANCELLATION_DRAIN_DURATION));
            return failure;
        }

        signalLifecycleFailure(originalFailure);
        cancelActiveTasks();
        if (awaitTasksUntil(minDeadline(taskDeadline, MAX_CANCELLATION_DRAIN_DURATION))) {
            return null;
        }
        return lifecycleFailure("Cancelled invocation tasks did not exit before the cleanup deadline", null);
    }

    private boolean awaitTasksUntil(long taskDeadline) throws InterruptedException {
        while (true) {
            var tasks = getActiveExecutorTasks();
            if (tasks.isEmpty()) {
                return true;
            }
            var remainingNanos = remainingNanos(taskDeadline);
            if (remainingNanos == 0) {
                return false;
            }
            try {
                CompletableFuture.allOf(
                                tasks.stream().map(ExecutorTaskHandle::exit).toArray(CompletableFuture[]::new))
                        .get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException impossible) {
                throw new IllegalStateException("Executor task exit signal failed", impossible.getCause());
            } catch (TimeoutException timeout) {
                return false;
            }
        }
    }

    private void cancelActiveTasks() {
        getActiveExecutorTasks().forEach(task -> task.cancel(true));
    }

    private long cleanupDeadlineNanos() {
        var localDeadline = addToNow(MAX_CLEANUP_DURATION);
        if (deadlineNanos == null) {
            return localDeadline;
        }
        return Math.min(localDeadline, deadlineNanos - RESPONSE_HEADROOM.toNanos());
    }

    void beginDraining() {
        synchronized (admissionLock) {
            if (lifecycleState == LifecycleState.OPEN) {
                lifecycleState = LifecycleState.DRAINING;
            }
        }
    }

    private void closeLifecycle() {
        synchronized (admissionLock) {
            lifecycleState = LifecycleState.CLOSED;
        }
    }

    private <T> T admitCheckpoint(Supplier<T> request) {
        synchronized (admissionLock) {
            if (!checkpointAdmissionOpen || lifecycleState == LifecycleState.CLOSED) {
                throw rejected("checkpoint request");
            }
            return request.get();
        }
    }

    private void stopCheckpointAdmission() {
        synchronized (admissionLock) {
            checkpointAdmissionOpen = false;
        }
    }

    private void requireOpen(String workType) {
        if (lifecycleState != LifecycleState.OPEN) {
            throw rejected(workType);
        }
    }

    private RejectedExecutionException rejected(String workType) {
        return new RejectedExecutionException(
                "Invocation " + invocationId + " is " + lifecycleState + "; cannot admit new " + workType);
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
        throw signalSuspension();
    }

    private SuspendExecutionException signalSuspension() {
        var ex = new SuspendExecutionException();
        stopAllOperations(ex);
        executionExceptionFuture.completeExceptionally(ex);
        return ex;
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

    /** Waits for the root result, suspension, or invocation deadline on the Lambda runtime thread. */
    <T> InvocationOutcome<T> awaitInvocationOutcome(CompletableFuture<T> userFuture) {
        var outcomeFuture = runUntilCompleteOrSuspend(userFuture);
        try {
            var result = deadlineNanos == null
                    ? outcomeFuture.get()
                    : outcomeFuture.get(
                            remainingNanos(deadlineNanos - EXECUTION_DEADLINE_HEADROOM.toNanos()),
                            TimeUnit.NANOSECONDS);
            return new InvocationOutcome<>(result, null);
        } catch (InterruptedException interrupted) {
            restoreInvocationThreadInterrupt = true;
            var failure = lifecycleFailure("Invocation runtime thread was interrupted", interrupted);
            signalLifecycleFailure(failure);
            return new InvocationOutcome<>(null, failure);
        } catch (TimeoutException timeout) {
            var failure = lifecycleFailure("Invocation deadline reached before execution completed", timeout);
            signalLifecycleFailure(failure);
            return new InvocationOutcome<>(null, failure);
        } catch (ExecutionException failure) {
            return new InvocationOutcome<>(null, ExceptionHelper.unwrapCompletableFuture(failure.getCause()));
        }
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

    private static String resolveInvocationId(Context lambdaContext, String durableExecutionArn) {
        if (lambdaContext != null) {
            var requestId = lambdaContext.getAwsRequestId();
            if (requestId != null && !requestId.isBlank()) {
                return requestId;
            }
        }
        return durableExecutionArn + "#local-" + LOCAL_INVOCATION_SEQUENCE.incrementAndGet();
    }

    private static Long resolveDeadlineNanos(Context lambdaContext) {
        if (lambdaContext == null) {
            return null;
        }
        var remainingMillis = Math.max(0L, lambdaContext.getRemainingTimeInMillis());
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis);
        var now = System.nanoTime();
        return now > Long.MAX_VALUE - remainingNanos ? Long.MAX_VALUE : now + remainingNanos;
    }

    private void signalLifecycleFailure(Throwable failure) {
        stopAllOperations(failure);
        executionExceptionFuture.completeExceptionally(failure);
    }

    private static UnrecoverableDurableExecutionException lifecycleFailure(String message, Throwable cause) {
        return new UnrecoverableDurableExecutionException(
                ErrorObject.builder()
                        .errorType(LIFECYCLE_ERROR_TYPE)
                        .errorMessage(message)
                        .build(),
                true,
                cause);
    }

    private static Throwable combineFailures(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    private static long minDeadline(long deadline, Duration maximumWait) {
        return Math.min(deadline, addToNow(maximumWait));
    }

    private static long addToNow(Duration duration) {
        var now = System.nanoTime();
        var nanos = duration.toNanos();
        return now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static Duration remainingDuration(long deadline) {
        return Duration.ofNanos(remainingNanos(deadline));
    }
}
