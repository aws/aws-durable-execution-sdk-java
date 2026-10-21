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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.SafeCloseable;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.PluginInfoConverter;
import software.amazon.lambda.durable.plugin.PluginRunner;
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

    private static final AtomicLong LOCAL_INVOCATION_SEQUENCE = new AtomicLong();

    // Stop waiting for the root early enough to cancel tasks and flush checkpoints before the platform deadline.
    private static final Duration EXECUTION_DEADLINE_HEADROOM = Duration.ofMillis(500);
    // Leave a final margin for response serialization and return through the Lambda runtime wrapper.
    private static final Duration RESPONSE_HEADROOM = Duration.ofMillis(100);
    // Lambda supplies a deadline. This fallback limits the initial cancellation phase for local/null contexts;
    // actual owner and transport exit are still awaited without a hard return bound.
    private static final Duration MAX_CLEANUP_DURATION = Duration.ofSeconds(30);
    private static final Duration MAX_GRACEFUL_DRAIN_DURATION = Duration.ofSeconds(5);
    private static final Duration MAX_CANCELLATION_DRAIN_DURATION = Duration.ofSeconds(1);
    private static final Duration CHECKPOINT_CLEANUP_RESERVE = Duration.ofMillis(100);
    private static final String LIFECYCLE_ERROR_TYPE =
            "software.amazon.lambda.durable.execution.InvocationLifecycleException";

    record InvocationOutcome<T>(T result, Throwable failure) {}

    enum LifecycleState {
        /** The invocation accepts new durable operations, executor tasks, checkpoints, and polls. */
        OPEN,

        /**
         * Invocation shutdown has started. Admitted child/coordinator tasks may create descendants, and registered
         * operations may submit tasks, checkpoint, or poll as they finish. New root and unrelated work is rejected.
         */
        DRAINING,

        /**
         * Work admission has ended; cleanup finishes enqueueing and flushing already-admitted checkpoints and polls.
         */
        CLOSED
    }

    // ===== Execution State =====
    private final Map<String, Operation> operationStorage;
    private final Operation executionOp;
    private final String durableExecutionArn;
    private final Context lambdaContext;
    private final AtomicReference<ExecutionMode> executionMode;
    private final DurableConfig durableConfig;
    private final Set<String> updatedOperationIdsSinceLastInvocation;
    private final Set<String> initialOperationIds;

    // ===== Plugins =====
    // Created per invocation, alongside this manager: the runner materializes one plugin instance per configured
    // factory when the invocation starts and releases the runner's references in close(). Started operation owners
    // retain only their matching cleanup recipients until they unwind, until the invocation drain has joined their
    // actual task.
    private final PluginRunner pluginRunner;
    private final AtomicReference<Error> pluginFatal;

    // ===== Invocation Lifecycle =====
    private final Object admissionLock = new Object();
    private final String invocationId;
    private final Long deadlineNanos;
    private final AtomicLong taskSequence = new AtomicLong();
    private final Map<Long, ExecutorTaskHandle<?>> activeExecutorTasks = new ConcurrentHashMap<>();
    // The current task is shared across managers on a thread; admission verifies the exact handle's owner.
    private static final ThreadLocal<ExecutorTaskHandle<?>> currentExecutorTask = new ThreadLocal<>();
    private volatile LifecycleState lifecycleState = LifecycleState.OPEN;
    private volatile boolean rootHandlerExited;
    private final CompletableFuture<Void> rootHandlerExitSignal = new CompletableFuture<>();
    private volatile boolean invocationReported;
    private boolean cleanupFinished;
    private final AtomicReference<UnrecoverableDurableExecutionException> lifecycleFailure = new AtomicReference<>();
    private final ThreadLocal<Boolean> cleanupInterrupted = ThreadLocal.withInitial(() -> false);
    // Guarded by admissionLock. Counts enqueue calls, not the lifetime of their returned futures.
    private int checkpointAdmissionsInFlight;

    // ===== Thread Coordination =====
    private final Map<String, BaseDurableOperation> registeredOperations = new ConcurrentHashMap<>();
    private final Map<String, Object> operationCompletionLocks = new ConcurrentHashMap<>();
    private final Set<String> activeThreads = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<ThreadContext> currentThreadContext = new ThreadLocal<>();
    private final CompletableFuture<Void> executionExceptionFuture = new CompletableFuture<>();
    // Requests and polling continuations; guarded by activeThreads so admission is atomic with last-thread suspension.
    private int checkpointRequestsInFlight;
    private volatile IllegalDurableOperationException invalidSuspensionException;
    // Guarded by activeThreads; an observation future can be cancelled independently of its actual task.
    private final Map<Object, BaseDurableOperation> checkpointContinuations = new HashMap<>();
    private boolean closing;
    // Guarded by activeThreads; selected against normal close before publishing callbacks outside the monitor.
    private UnrecoverableDurableExecutionException selectedContinuationFailure;

    /**
     * Per-wait state used to coordinate the caller thread with the future completion callback.
     *
     * <p>{@code completed} prevents the caller from deregistering after the future has already settled. {@code waiting}
     * records that the caller did deregister, so completion should register it again. Both fields must always be read
     * and written while synchronized on this instance.
     */
    private static final class CompletionWait {
        private boolean completed;
        private boolean waiting;
    }

    // ===== Checkpoint Batching =====
    private final CheckpointManager checkpointManager;

    public ExecutionManager(DurableExecutionInput input, DurableConfig config, Context lambdaContext) {
        this(input, config, lambdaContext, new AtomicReference<>());
    }

    ExecutionManager(
            DurableExecutionInput input,
            DurableConfig config,
            Context lambdaContext,
            AtomicReference<Error> pluginFatal) {
        durableConfig = config;
        this.pluginFatal = pluginFatal;
        this.pluginRunner = new PluginRunner(config.getPluginFactories(), this::failFromPlugin);
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
                this::finishCheckpointProcessing,
                this::signalSuspension,
                pluginFatal::get,
                this::checkCheckpointRequestAllowed);

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

    // ===== State Management =====

    /**
     * Returns this invocation's plugin dispatcher. Scoped to this manager, i.e. to this invocation.
     *
     * @return PluginRunner instance (never null)
     */
    public PluginRunner getPluginRunner() {
        return pluginRunner;
    }

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
        registerOperation(operation, null);
    }

    /**
     * Registers an operation so it can receive checkpoint completion notifications.
     *
     * @param operation operation to register
     * @param completionLockParent parent operation whose completion lock should also serialize this operation, or null
     */
    public void registerOperation(BaseDurableOperation operation, BaseDurableOperation completionLockParent) {
        // Identity/parent lock lookup can be implemented by an operation subclass. Keep it outside admissionLock,
        // just like checkpoint enqueue calls, so an in-flight token withdrawal cannot be blocked by registration.
        synchronized (admissionLock) {
            requireOperationAdmission();
        }
        var operationId = operation.getOperationId();
        var completionLock = completionLockParent == null
                ? operationCompletionLocks.computeIfAbsent(operationId, ignored -> new Object())
                : completionLockFor(completionLockParent);
        synchronized (admissionLock) {
            requireOperationAdmission();
            operationCompletionLocks.put(operationId, completionLock);
            registeredOperations.put(operationId, operation);
        }
        // Revocation is published before existing operations are suspended. Check after insertion so an
        // operation registered too late for that notification still releases its waiters.
        if (isCheckpointTokenRevoked()) {
            operation.getCompletionFuture().completeExceptionally(new SuspendExecutionException());
        }
    }

    /** Submits the invocation's root handler and records its executor task separately from its logical result. */
    <T> CompletableFuture<T> submitRootTask(Supplier<T> action) {
        return submitExecutorTask(ExecutorTaskHandle.Role.ROOT, null, durableConfig.getExecutorService(), action);
    }

    /** Submits an operation handler and records its executor task separately from its logical result. */
    public CompletableFuture<Void> submitOperationTask(BaseDurableOperation operation, Runnable action) {
        return submitOperationTask(operation, action, false);
    }

    /** Tracks an operation wrapper and settles skipped owners only after their registered task has exited. */
    public void executeOperationTask(
            BaseDurableOperation operation, Runnable command, Consumer<Throwable> onUnstartedExit) {
        var started = new AtomicBoolean();
        ExecutorTaskHandle<Void> task = registerExecutorTask(
                operationRole(operation),
                operation,
                durableConfig.getExecutorService(),
                () -> {
                    started.set(true);
                    command.run();
                    return null;
                },
                true);
        task.exit().thenRun(() -> {
            if (!started.get()) {
                // A queued cancellation can exit without entering the operation's wrapper. Logical cancellation
                // of an already-running action must not settle that owner's private completion early.
                task.completion().whenComplete((ignored, failure) -> onUnstartedExit.accept(failure));
            }
        });
    }

    private CompletableFuture<Void> submitOperationTask(
            BaseDurableOperation operation, Runnable action, boolean preserveWorkerFailure) {
        return submitExecutorTask(
                operationRole(operation),
                operation,
                durableConfig.getExecutorService(),
                () -> {
                    action.run();
                    return null;
                },
                preserveWorkerFailure);
    }

    private static ExecutorTaskHandle.Role operationRole(BaseDurableOperation operation) {
        return operation.getType() == OperationType.STEP
                ? ExecutorTaskHandle.Role.STEP
                : switch (operation.getSubType()) {
                    case MAP, PARALLEL -> ExecutorTaskHandle.Role.COORDINATOR;
                    default -> ExecutorTaskHandle.Role.CHILD_CONTEXT;
                };
    }

    private <T> CompletableFuture<T> submitExecutorTask(
            ExecutorTaskHandle.Role role,
            BaseDurableOperation operation,
            ExecutorService executor,
            Supplier<T> action) {
        return submitExecutorTask(role, operation, executor, action, false);
    }

    /** Tracks the existing plugin worker command without absorbing its intentional worker failures. */
    void executeRootTask(Runnable command) {
        submitExecutorTask(
                ExecutorTaskHandle.Role.ROOT,
                null,
                durableConfig.getExecutorService(),
                () -> {
                    command.run();
                    return null;
                },
                true);
    }

    private <T> CompletableFuture<T> submitExecutorTask(
            ExecutorTaskHandle.Role role,
            BaseDurableOperation operation,
            ExecutorService executor,
            Supplier<T> action,
            boolean preserveWorkerFailure) {
        return registerExecutorTask(role, operation, executor, action, preserveWorkerFailure)
                .completion();
    }

    private <T> ExecutorTaskHandle<T> registerExecutorTask(
            ExecutorTaskHandle.Role role,
            BaseDurableOperation operation,
            ExecutorService executor,
            Supplier<T> action,
            boolean preserveWorkerFailure) {
        ExecutorTaskHandle<T> task;
        synchronized (admissionLock) {
            requireTaskAdmission(operation);
            var taskId = taskSequence.incrementAndGet();
            var operationId = operation == null ? null : operation.getOperationId();
            task = new ExecutorTaskHandle<>(taskId, role, operationId, () -> activeExecutorTasks.remove(taskId));
            activeExecutorTasks.put(task.id(), task);
        }

        try {
            dispatchExecutorTask(task, action, executor, preserveWorkerFailure);
            return task;
        } catch (RuntimeException | Error failure) {
            task.submissionFailed(failure);
            throw failure;
        }
    }

    private <T> void dispatchExecutorTask(
            ExecutorTaskHandle<T> task, Supplier<T> action, ExecutorService executor, boolean preserveWorkerFailure) {
        var escapedFailure = new AtomicReference<Throwable>();
        var execution = new FutureTask<Void>(
                () -> runExecutorTask(task, () -> {
                    try {
                        return action.get();
                    } catch (Throwable failure) {
                        if (preserveWorkerFailure) escapedFailure.set(failure);
                        throw failure;
                    }
                }),
                null);
        // Binding precedes execute because CallerRunsPolicy may execute synchronously.
        task.bindExecution(execution);
        executor.execute(() -> {
            execution.run();
            // FutureTask observes failure for cancellation/exit bookkeeping, but a lifecycle worker's deliberate
            // escape must still reach the executor so a dirty or fatally failed worker is not silently reused.
            var failure = escapedFailure.get();
            if (failure != null) ExceptionHelper.sneakyThrow(failure);
        });
    }

    private <T> void runExecutorTask(ExecutorTaskHandle<T> task, Supplier<T> action) {
        var previous = currentExecutorTask.get();
        currentExecutorTask.set(task);
        try {
            task.run(action);
        } finally {
            if (previous == null) {
                currentExecutorTask.remove();
            } else {
                currentExecutorTask.set(previous);
            }
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
        if (!pluginRunner.isEmpty()) notifyCheckpointChangesBeforeCompletion(newOperations);

        newOperations.forEach(op -> {
            // Keep storage publication and waiter notification atomic: publishing terminal state ahead of completion
            // can make a waiter attempt to suspend despite there being no pending operation.
            registeredOperations.compute(op.id(), (id, registeredOperation) -> {
                if (registeredOperation == null) {
                    operationStorage.put(op.id(), op);
                } else {
                    processCheckpointUpdate(registeredOperation, op, () -> operationStorage.put(op.id(), op));
                }
                return registeredOperation;
            });
        });
    }

    private void notifyCheckpointChangesBeforeCompletion(List<Operation> newOperations) {
        var updatedOperations = new ArrayList<Operation>();
        var checkpointSnapshot = new HashMap<>(operationStorage);
        for (var operation : newOperations) {
            var previous = checkpointSnapshot.put(operation.id(), operation);
            if (previous == null || previous.status() != operation.status()) {
                updatedOperations.add(operation);
            }
        }

        // Observe the complete response before releasing any terminal waiter. A fatal observer failure must stop
        // this checkpoint's continuation work. The payload sees new state without publishing terminal storage early.
        if (!updatedOperations.isEmpty()) {
            var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
            pluginRunner.onOperationChange(PluginInfoConverter.toOperationChangeInfo(
                    requestId,
                    durableExecutionArn,
                    updatedOperations,
                    checkpointSnapshot.values(),
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

    Object completionLockFor(BaseDurableOperation operation) {
        return operationCompletionLocks.computeIfAbsent(operation.getOperationId(), ignored -> new Object());
    }

    /**
     * Publishes a checkpoint update and notifies the operation under the same manager-owned lock used by operation
     * waiters.
     *
     * @param operation durable operation receiving the checkpoint update
     * @param checkpointedOperation the updated operation state
     * @param publishUpdate publishes the operation update to execution state
     */
    public void processCheckpointUpdate(
            BaseDurableOperation operation, Operation checkpointedOperation, Runnable publishUpdate) {
        synchronized (completionLockFor(operation)) {
            publishUpdate.run();
            operation.onCheckpointComplete(checkpointedOperation);
        }
    }

    /**
     * Completes an operation future under the same lock used by waiters and checkpoint update delivery.
     *
     * @param operation operation to mark complete
     */
    public void completeOperation(BaseDurableOperation operation) {
        synchronized (completionLockFor(operation)) {
            operation.getCompletionFuture().complete(operation);
        }
    }

    /**
     * Waits for a single operation to settle, deregistering the current thread while the operation is still in progress
     * so the execution can suspend.
     *
     * @param operation operation to wait on
     */
    public void waitForOperationCompletion(BaseDurableOperation operation) {
        validateCurrentThreadCanWaitForDurableOperation(operation.getType(), operation.getName());

        var threadContext = getCurrentThreadContext();
        var completionFuture = operation.getCompletionFuture();
        CompletableFuture<?> future = operation.getCompletionFuture();

        // It's important that we synchronize access to the future. Otherwise, a race condition could happen if the
        // completionFuture is completed by a user thread (a step or child context thread) when the execution here
        // is between `isOperationCompleted` and `thenRun`.
        // If this operation is a branch/iteration of a ConcurrencyOperation (map or parallel), the branches/iterations
        // must be completed sequentially to avoid race conditions.
        var completionLock = completionLockFor(operation);
        synchronized (completionLock) {
            if (!completionFuture.isDone()) {
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
    }

    /**
     * Waits for the first of the given operations to settle and returns it.
     *
     * <p>This is the multi-operation equivalent of {@code BaseDurableOperation.waitForOperationCompletion()}: it builds
     * a combined future so this manager can suspend while waiting for the first operation to settle.
     *
     * @param operations operations to wait on
     * @return the first durable future to settle
     */
    public DurableFuture<?> waitForFirstOperationCompletion(List<? extends BaseDurableOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("waitForFirstOperationCompletion requires at least one operation");
        }

        validateCurrentThreadCanWaitForDurableOperation(null, null);

        var threadId = getCurrentThreadContext().threadId();
        var wait = new CompletionWait();
        var anyOfFuture = CompletableFuture.anyOf(operations.stream()
                .map(BaseDurableOperation::getCompletionFuture)
                .toArray(CompletableFuture[]::new));

        // Attach the callback before the caller decides whether to deregister. This ensures the combined future cannot
        // settle between "is it done?" and "deregister the caller thread" without updating the wait state below.
        var reactivationFuture = anyOfFuture.whenComplete((ignored, failure) -> {
            synchronized (wait) {
                // Completion may happen before or after the caller deregisters.
                //     - If the caller has not deregistered yet, completed=true tells it to stay active.
                //     - If it has, waiting=true tells this callback to wake it.
                wait.completed = true;
                if (wait.waiting) {
                    wait.waiting = false;
                    if (failure == null) {
                        registerActiveThread(threadId);
                    }
                }
            }
        });

        synchronized (wait) {
            if (!wait.completed) {
                // The combined future is still pending, so the current thread can deregister and allow suspension.
                // On success, the completion callback observes waiting=true and registers this thread again before the
                // join below returns.
                wait.waiting = true;
                deregisterActiveThread(threadId);
            }
        }

        Object firstCompletedOperation;
        try {
            firstCompletedOperation = reactivationFuture.join();
        } catch (Throwable throwable) {
            ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(throwable));
            return null;
        }
        if (firstCompletedOperation instanceof DurableFuture<?> future) {
            return future;
        }

        var exception = new IllegalDurableOperationException("Expected first completion future to complete with a "
                + DurableFuture.class.getSimpleName() + ", got: "
                + (firstCompletedOperation == null
                        ? "null"
                        : firstCompletedOperation.getClass().getName()));
        terminateExecution(exception);
        throw exception;
    }

    /** Validates that the current thread can wait for a durable operation to complete. */
    public void validateCurrentThreadCanWaitForDurableOperation(OperationType operationType, String operationName) {
        var currentThreadType = getCurrentThreadContext().threadType();
        if (currentThreadType != ThreadType.STEP) {
            return;
        }

        var errorMessage = operationType == null
                ? "Nested durable operation is not supported from within a " + currentThreadType + " execution."
                : String.format(
                        "Nested %s operation is not supported on %s from within a %s execution.",
                        operationType, operationName, currentThreadType);
        throw new IllegalStateException(errorMessage);
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

    /** Removes work that was never accepted by its executor; the dispatch caller propagates the submission error. */
    public void cancelThreadRegistration(String threadId) {
        synchronized (activeThreads) {
            activeThreads.remove(threadId);
        }
    }

    boolean tryStartCheckpointProcessing() {
        synchronized (activeThreads) {
            if (executionExceptionFuture.isDone() || pluginFatal.get() != null) {
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

    /**
     * Runs a polling continuation away from the serialized checkpoint batcher. Keeps checkpoint processing active from
     * receiving the callback until the continuation has registered its next worker (or finished inline). Canceling the
     * returned future affects observation only; queued or running work still owns its activity lease.
     *
     * @param continuation operation work to dispatch after receiving a polling update
     * @return completion of the continuation, or an already-completed future when execution has stopped
     */
    public CompletableFuture<Void> runCheckpointContinuation(Runnable continuation) {
        return runCheckpointContinuation(null, continuation, InternalExecutor.INSTANCE);
    }

    /** Schedules an operation continuation whose unfinished waiters are stopped during manager cleanup. */
    public CompletableFuture<Void> runCheckpointContinuation(BaseDurableOperation owner, Runnable continuation) {
        return runCheckpointContinuation(owner, continuation, InternalExecutor.INSTANCE);
    }

    CompletableFuture<Void> runCheckpointContinuation(Runnable continuation, Executor coordinator) {
        return runCheckpointContinuation(null, continuation, coordinator);
    }

    CompletableFuture<Void> runCheckpointContinuation(
            BaseDurableOperation owner, Runnable continuation, Executor coordinator) {
        var registration = registerCheckpointContinuation(owner);
        if (registration == null) {
            if (isClosing()) stopContinuationOwner(owner);
            return CompletableFuture.completedFuture(null);
        }
        try {
            var completion = new CompletableFuture<Void>();
            coordinator.execute(() -> completeCheckpointContinuation(owner, registration, continuation, completion));
            return completion;
        } catch (RuntimeException | Error failure) {
            try {
                if ((owner != null && !isClosing())
                        || failure instanceof VirtualMachineError
                        || failure instanceof ThreadDeath) signalContinuationFailure(failure);
            } finally {
                finishCheckpointContinuation(registration);
            }
            throw failure;
        }
    }

    @SuppressWarnings("removal")
    private void completeCheckpointContinuation(
            BaseDurableOperation owner,
            Object registration,
            Runnable continuation,
            CompletableFuture<Void> completion) {
        try {
            try {
                if (!isClosing()) continuation.run();
            } catch (Throwable failure) {
                // An operation polling chain can ignore observation. Select retry control before the final lease
                // could select PENDING; unowned ordinary helper failures retain their observation-only contract.
                if ((owner != null && !isClosing())
                        || failure instanceof VirtualMachineError
                        || failure instanceof ThreadDeath) signalContinuationFailure(failure);
                throw failure;
            } finally {
                finishCheckpointContinuation(registration);
            }
            completion.complete(null);
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
            // The observation future may be ignored by a polling chain. Never hide a JVM fatal from its worker.
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
        }
    }

    private void signalContinuationFailure(Throwable failure) {
        var control = new UnrecoverableDurableExecutionException(
                ErrorObject.builder()
                        .errorType(failure.getClass().getName())
                        .errorMessage("Error in SDK checkpoint continuation")
                        .build(),
                true,
                failure);
        UnrecoverableDurableExecutionException selected;
        synchronized (activeThreads) {
            if (closing && !(failure instanceof VirtualMachineError || failure instanceof ThreadDeath)) return;
            if (selectedContinuationFailure == null) selectedContinuationFailure = control;
            selected = selectedContinuationFailure;
        }
        // A later admitted publisher may drain the winner's callbacks. Keep that CompletableFuture behavior,
        // but publish/wake using the selected cause, outside the monitor needed by worker cleanup.
        if (executionExceptionFuture.completeExceptionally(selected)) stopAllOperations(selected);
    }

    private Object registerCheckpointContinuation(BaseDurableOperation owner) {
        synchronized (activeThreads) {
            if (closing || executionExceptionFuture.isDone()) return null;
            var registration = new Object();
            checkpointContinuations.put(registration, owner);
            checkpointRequestsInFlight++;
            return registration;
        }
    }

    private void finishCheckpointContinuation(Object registration) {
        synchronized (activeThreads) {
            // An inline coordinator may rethrow after its task already released this registration.
            if (!checkpointContinuations.containsKey(registration)) return;
            checkpointContinuations.remove(registration);
            try {
                finishCheckpointProcessing();
            } finally {
                activeThreads.notifyAll();
            }
        }
    }

    private boolean isClosing() {
        synchronized (activeThreads) {
            return closing;
        }
    }

    private static void stopContinuationOwner(BaseDurableOperation owner) {
        if (owner != null) owner.getCompletionFuture().completeExceptionally(new SuspendExecutionException());
    }

    private boolean shouldSuspendExecution() {
        return !closing
                && activeThreads.isEmpty()
                && checkpointRequestsInFlight == 0
                && !executionExceptionFuture.isDone()
                && pluginFatal.get() == null;
    }

    private void preSuspendCheck() {
        var hasAnyPendingOperation = operationStorage.values().stream().anyMatch(o -> switch (o.type()) {
            case STEP -> o.status() == OperationStatus.PENDING;
            case WAIT, CALLBACK -> o.status() == OperationStatus.STARTED;
            case CHAINED_INVOKE -> o.status() == OperationStatus.PENDING || o.status() == OperationStatus.STARTED;
            default -> false;
        });

        if (!hasAnyPendingOperation && !isCheckpointTokenRevoked()) {
            var waitingOperations = registeredOperations.values().stream()
                    .filter(op -> !op.getCompletionFuture().isDone())
                    .map(op -> String.format(
                            "%s (id=%s)", Objects.toString(op.getName(), "<unnamed>"), op.getOperationId()))
                    .distinct()
                    .toList();

            var errorMessage = "Invalid suspension. No operation is pending. Suspended threads were waiting for: "
                    + waitingOperations;
            logger.error(errorMessage);
            this.invalidSuspensionException = new IllegalDurableOperationException(errorMessage, true);
        }
    }

    // ===== Checkpointing =====

    // This method will checkpoint the operation updates to the durable backend and return a future which completes
    // when the checkpoint completes.
    public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
        return admitCheckpoint(() -> checkpointManager.checkpoint(update));
    }

    // ===== Polling =====

    // This method will poll the operation updates from the durable backend and return a future which completes
    // when an update of the operation is received.
    // This is useful for in-process waits. For example, we want to
    // wait while another thread is still running, and we therefore are not
    // re-invoked because we never suspended.
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId) {
        return admitCheckpoint(
                () -> checkpointManager.pollForUpdate(operationId),
                () -> CompletableFuture.failedFuture(new SuspendExecutionException()));
    }

    /**
     * Pools for operation updates at a specific time
     *
     * @param operationId the operation id to poll for updates
     * @param at the time to poll for updates
     * @return a completable future that completes with the operation update
     */
    public CompletableFuture<Operation> pollForOperationUpdates(String operationId, Instant at) {
        return admitCheckpoint(
                () -> checkpointManager.pollForUpdate(operationId, at),
                () -> CompletableFuture.failedFuture(new SuspendExecutionException()));
    }

    /** Reserved result delivery after operation admission has closed; the caller observes this checkpoint. */
    CompletableFuture<Void> sendExecutionResultUpdate(OperationUpdate update) {
        checkCheckpointRequestAllowed();
        return checkpointManager.checkpoint(update);
    }

    void awaitCheckpointCompletion(CompletableFuture<Void> future) {
        var failure = observeCheckpointUntilDeadline(future);
        // A cutoff may settle the logical observation early. Main's End contract still requires admitted
        // transport and its publication callbacks to finish before the owner finalizes the invocation.
        try {
            checkpointManager.shutdown();
        } catch (Throwable transportFailure) {
            failure = combineFailures(failure, ExceptionHelper.unwrapInvocationFailure(transportFailure));
        }
        var completedFailure = future.isDone()
                ? future.handle((ignored, problem) -> ExceptionHelper.unwrapInvocationFailure(problem))
                        .join()
                : null;
        if (isFatal(completedFailure)) ExceptionHelper.sneakyThrow(completedFailure);
        if (isFatal(failure)) ExceptionHelper.sneakyThrow(failure);
        if (completedFailure != null && !isLifecycleFailure(completedFailure))
            ExceptionHelper.sneakyThrow(completedFailure);
        if (checkpointManager.isTerminalExecutionAcknowledged()) return;
        if (failure != null) ExceptionHelper.sneakyThrow(failure);
        future.join();
    }

    private Throwable observeCheckpointUntilDeadline(CompletableFuture<Void> future) {
        var deadline =
                deadlineNanos == null ? addToNow(MAX_CLEANUP_DURATION) : deadlineNanos - RESPONSE_HEADROOM.toNanos();
        try {
            future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return lifecycleFailure("Interrupted while waiting for checkpoint completion", interrupted);
        } catch (TimeoutException timeout) {
            return lifecycleFailure("Checkpoint completion exceeded the invocation deadline", timeout);
        } catch (CancellationException cancelled) {
            // Newer JDKs wrap get() cancellation. Preserve the future's actual failure and still drain transport.
            return future.handle((ignored, failure) -> ExceptionHelper.unwrapInvocationFailure(failure))
                    .join();
        } catch (ExecutionException failure) {
            return ExceptionHelper.unwrapInvocationFailure(failure.getCause());
        }
    }

    void markRootHandlerExited() {
        rootHandlerExited = true;
        rootHandlerExitSignal.complete(null);
    }

    void markInvocationReported() {
        invocationReported = true;
    }

    boolean isInvocationReported() {
        return invocationReported;
    }

    UnrecoverableDurableExecutionException getLifecycleFailure() {
        return lifecycleFailure.get();
    }

    boolean isTerminalExecutionAcknowledged() {
        return checkpointManager.isTerminalExecutionAcknowledged();
    }

    /** Fences actual user callbacks, including callbacks delayed inside a plugin start hook. */
    public void checkUserFunctionExecutionAllowed() {
        rethrowPluginFatalIfPresent();
        if (executionExceptionFuture.isCompletedExceptionally()) {
            try {
                executionExceptionFuture.join();
            } catch (CompletionException stopped) {
                ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapInvocationFailure(stopped));
            }
        }
        checkInvocationAdmission();
    }

    private void checkInvocationAdmission() {
        if (invocationReported || lifecycleFailure.get() != null) {
            var failure = lifecycleFailure.get();
            throw failure != null ? failure : lifecycleFailure("Invocation has already returned", null);
        }
        if (deadlineNanos != null && remainingNanos(deadlineNanos - EXECUTION_DEADLINE_HEADROOM.toNanos()) == 0) {
            var failure = lifecycleFailure("Invocation deadline reached before user work", new TimeoutException());
            // An existing suspension/termination already owns the outcome; still reject this later admission.
            if (executionExceptionFuture.isCompletedExceptionally()) throw failure;
            // This check may hold admissionLock. Latch without running cancellation/completion observers here,
            // so catching the rejection cannot turn the invocation into a successful fallback.
            lifecycleFailure.compareAndSet(null, failure);
            throw lifecycleFailure.get();
        }
    }

    private void checkCheckpointRequestAllowed() {
        rethrowPluginFatalIfPresent();
        if (invocationReported || lifecycleFailure.get() != null) {
            var failure = lifecycleFailure.get();
            throw failure != null ? failure : lifecycleFailure("Invocation has already returned", null);
        }
        if (deadlineNanos != null && remainingNanos(deadlineNanos - RESPONSE_HEADROOM.toNanos()) == 0) {
            var failure = lifecycleFailure("Invocation deadline reached before checkpoint I/O", new TimeoutException());
            if (executionExceptionFuture.isCompletedExceptionally()) throw failure;
            lifecycleFailure.compareAndSet(null, failure);
            throw lifecycleFailure.get();
        }
    }

    // ===== Utilities =====
    /** Shutdown the checkpoint batcher. */
    @Override
    public void close() {
        try {
            var failure = finishInvocation(null);
            if (failure != null) ExceptionHelper.sneakyThrow(failure);
        } finally {
            pluginRunner.releasePlugins();
        }
    }

    /**
     * Settles submitted operation handlers and stops polling before flushing checkpoints. Terminal decisions therefore
     * observe token withdrawal without allowing later polls to change that decision. Safe to call more than once: a
     * second call finds no running handlers and an already-stopped batcher.
     */
    void drainOperations() {
        // Closing admission can skip queued continuations. Stop their owners before joining user handlers,
        // or a child waiting on a skipped continuation would prevent the drain from completing.
        beginDraining();
        var drain = new DrainFailure();
        drain.run(() -> stopCheckpointContinuations(drain));
        drain.run(() -> waitForRunningUserHandlers(drain));
        drain.run(() -> drainExecutorTasks(drain));
        drain.run(() -> closeLifecycle(drain));
        drain.run(this::validateExecutorPool);
        drain.run(checkpointManager::shutdown);
        drain.finish();
    }

    private void stopCheckpointContinuations(DrainFailure drain) {
        List<BaseDurableOperation> owners;
        synchronized (activeThreads) {
            closing = true;
            owners = new ArrayList<>(checkpointContinuations.values());
        }
        owners.forEach(owner -> drain.run(() -> stopContinuationOwner(owner)));
        synchronized (activeThreads) {
            while (!checkpointContinuations.isEmpty()) {
                try {
                    // wait releases this monitor so tasks can finish and release their registrations. No user work
                    // or future join is performed while holding the coordination monitor.
                    activeThreads.wait();
                } catch (InterruptedException interrupted) {
                    drain.interrupted("Interrupted while waiting for checkpoint continuations", interrupted);
                }
            }
        }
    }

    private void waitForRunningUserHandlers(DrainFailure drain) {
        for (var operation : registeredOperations.values()) {
            drain.run(() -> awaitUserHandler(operation, drain));
        }
    }

    private void awaitUserHandler(BaseDurableOperation operation, DrainFailure drain) {
        var completion = operation.getRunningUserHandler();
        if (completion == null) return;
        while (!completion.isDone()) {
            try {
                completion.get();
            } catch (InterruptedException interrupted) {
                // get clears the flag. Finish all owned work before restoring it and reporting RETRYING.
                drain.interrupted(
                        "Interrupted while waiting for user handler " + operation.getOperationId(), interrupted);
            } catch (CancellationException cancelled) {
                drain.record(new IllegalStateException(
                        "User handler observation cancelled: " + operation.getOperationId(), cancelled));
                return;
            } catch (Exception completedExceptionally) {
                return; // The real owner settled exceptionally; its selected outcome is handled separately.
            }
        }
    }

    /** Retains cleanup failures while every required stage gets its finalization opportunity. */
    private static final class DrainFailure {
        private Throwable failure;
        private boolean fatal;
        private boolean interrupted;
        private final boolean lifecycleInterruptions;

        private DrainFailure() {
            this(false);
        }

        private DrainFailure(boolean lifecycleInterruptions) {
            this.lifecycleInterruptions = lifecycleInterruptions;
        }

        void run(Runnable stage) {
            try {
                stage.run();
            } catch (Throwable problem) {
                record(problem);
            }
        }

        void interrupted(String message, InterruptedException problem) {
            if (!interrupted)
                record(
                        lifecycleInterruptions
                                ? lifecycleFailure(message, problem)
                                : new IllegalStateException(message, problem));
            interrupted = true;
        }

        void preserveInterrupt() {
            interrupted = true;
        }

        @SuppressWarnings("removal")
        void record(Throwable problem) {
            var cause = ExceptionHelper.unwrapInvocationFailure(problem);
            boolean nextFatal = cause instanceof VirtualMachineError || cause instanceof ThreadDeath;
            // Keep a transport wrapper intact: close may run while the same fatal already escapes the body.
            // Throwing that fatal object again from try-with-resources would trigger self-suppression.
            var next = problem;
            if (failure == null || nextFatal && !fatal) {
                if (failure != null && failure != next) next.addSuppressed(failure);
                failure = next;
                fatal = nextFatal;
            } else if (failure != next) {
                failure.addSuppressed(next);
            }
        }

        void finish() {
            if (interrupted) Thread.currentThread().interrupt();
            if (failure != null) ExceptionHelper.sneakyThrow(failure);
        }
    }

    void beginDraining() {
        synchronized (admissionLock) {
            if (lifecycleState == LifecycleState.OPEN) {
                lifecycleState = LifecycleState.DRAINING;
            }
        }
    }

    private void closeLifecycle(DrainFailure drain) {
        synchronized (admissionLock) {
            lifecycleState = LifecycleState.CLOSED;
            // Admission must stop before the final flush, including calls admitted before CLOSED but not yet enqueued.
            while (checkpointAdmissionsInFlight > 0) {
                try {
                    admissionLock.wait();
                } catch (InterruptedException e) {
                    // Preserve the existing checkpoint-admission contract: finish its enqueue without
                    // turning the caller's interrupt into a new execution failure.
                    drain.preserveInterrupt();
                }
            }
        }
    }

    private <T> T admitCheckpoint(Supplier<T> request) {
        return admitCheckpoint(request, () -> {
            throw rejected("checkpoint request");
        });
    }

    private <T> T admitCheckpoint(Supplier<T> request, Supplier<T> whenClosed) {
        synchronized (admissionLock) {
            if (lifecycleState == LifecycleState.CLOSED) {
                return whenClosed.get();
            }
            checkpointAdmissionsInFlight++;
        }
        try {
            // CheckpointManager completes continuations under its polling monitor. Those continuations may submit
            // tasks or poll again, so never call it while holding admissionLock.
            return request.get();
        } finally {
            synchronized (admissionLock) {
                if (--checkpointAdmissionsInFlight == 0) {
                    admissionLock.notifyAll();
                }
            }
        }
    }

    private void requireOperationAdmission() {
        var task = currentExecutorTask.get();
        if (lifecycleState == LifecycleState.DRAINING
                && task != null
                && task.state() == ExecutorTaskHandle.State.RUNNING
                && activeExecutorTasks.get(task.id()) == task
                && (task.role() == ExecutorTaskHandle.Role.CHILD_CONTEXT
                        || task.role() == ExecutorTaskHandle.Role.COORDINATOR)) {
            // Descendants are part of the admitted task's work, including branches that start during draining.
            return;
        }
        requireOpen("durable operation");
    }

    private void requireTaskAdmission(BaseDurableOperation operation) {
        if (lifecycleState == LifecycleState.DRAINING
                && operation != null
                && registeredOperations.get(operation.getOperationId()) == operation) {
            // Map/parallel branches are registered before their coordinator submits them to the executor.
            return;
        }
        requireOpen("task");
    }

    private void requireOpen(String workType) {
        checkInvocationAdmission();
        if (lifecycleState != LifecycleState.OPEN) {
            throw rejected(workType);
        }
    }

    private RejectedExecutionException rejected(String workType) {
        return new RejectedExecutionException(
                "Invocation " + invocationId + " is " + lifecycleState + "; cannot admit new " + workType);
    }

    private void drainExecutorTasks(DrainFailure drain) {
        while (true) {
            var tasks = operationTasksToDrain();
            if (tasks.isEmpty()) return;
            for (var task : tasks) awaitTaskExit(task, drain);
        }
    }

    private List<ExecutorTaskHandle<?>> operationTasksToDrain() {
        synchronized (admissionLock) {
            var tasks = activeExecutorTasks.values().stream()
                    .filter(task -> task.role() != ExecutorTaskHandle.Role.ROOT)
                    .toList();
            if (tasks.isEmpty()) {
                // Close under the submission lock so no task can enter after the final empty snapshot.
                lifecycleState = LifecycleState.CLOSED;
            }
            return tasks;
        }
    }

    private void awaitTaskExit(ExecutorTaskHandle<?> task, DrainFailure drain) {
        while (!task.exit().isDone()) {
            try {
                task.exit().get();
            } catch (InterruptedException e) {
                drain.interrupted("Interrupted while draining operation task: " + task.operationId(), e);
            } catch (ExecutionException e) {
                drain.record(new IllegalStateException("Could not drain operation task: " + task.operationId(), e));
                return;
            }
        }
    }

    private void validateExecutorPool() {
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
        // Select control flow before waking a handler whose finally block can complete its body future.
        executionExceptionFuture.completeExceptionally(exception);
        stopAllOperations(exception);
        throw exception;
    }

    /** Suspends the execution by completing the execution exception future with a {@link SuspendExecutionException}. */
    public void suspendExecution() {
        throw signalSuspension();
    }

    private SuspendExecutionException signalSuspension() {
        var ex = new SuspendExecutionException();
        executionExceptionFuture.completeExceptionally(ex);
        stopAllOperations(ex);
        return ex;
    }

    /**
     * returns {@code true} if the execution is terminated exceptionally (with a {@link SuspendExecutionException} or an
     * unrecoverable error).
     */
    public boolean isExecutionCompletedExceptionally() {
        return executionExceptionFuture.isCompletedExceptionally();
    }

    Error getInvocationFatal() {
        return pluginFatal.get();
    }

    /** Reports a selected fatal and requests cooperative peer cancellation before the invocation drains. */
    void recordInvocationFatal(Error fatal) {
        failFromPlugin(fatal);
    }

    private void failFromPlugin(Error fatal) {
        var original = recordPluginFatal(fatal);
        executionExceptionFuture.completeExceptionally(original);
        stopAllOperations(original);
        checkpointManager.abortPending(original);
    }

    /** Reports a root body fatal before owned cleanup without replacing already-selected suspension/retry control. */
    Error recordRootHandlerFatal(Error fatal) {
        // Reuse the existing first-completion channel. A losing body-finally failure is not an instrumentation fatal.
        if (!executionExceptionFuture.completeExceptionally(fatal)) return null;
        var original = recordPluginFatal(fatal);
        stopAllOperations(original);
        checkpointManager.abortPending(original);
        return original;
    }

    /** Stops pending work while leaving the root worker responsible for completing its scope cleanup. */
    void recordHandlerScopeFatal(Error fatal) {
        var original = recordPluginFatal(fatal);
        // Publish control immediately; the root handler still owns cleanup and End before returning.
        executionExceptionFuture.completeExceptionally(original);
        stopAllOperations(original);
        checkpointManager.abortPending(original);
    }

    private Error recordPluginFatal(Error fatal) {
        pluginFatal.compareAndSet(null, fatal);
        var original = pluginFatal.get();
        synchronized (activeThreads) {
            activeThreads.notifyAll();
        }
        return original;
    }

    /** Once plugin instrumentation has failed fatally, do not retry or persist unrelated operation outcomes. */
    public void rethrowPluginFatalIfPresent() {
        var fatal = pluginFatal.get();
        if (fatal != null) throw fatal;
    }

    public IllegalDurableOperationException getInvalidSuspensionException() {
        return invalidSuspensionException;
    }

    public boolean isCheckpointTokenRevoked() {
        return checkpointManager.isCheckpointTokenRevoked();
    }

    private void stopAllOperations(Throwable cause) {
        registeredOperations.values().forEach(op -> {
            op.getCompletionFuture().completeExceptionally(cause);
            if (pluginFatal.get() != null) op.interruptRunningUserHandler();
        });
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
    /** Called only by the invocation caller, so root exit and owned-executor cleanup never self-join. */
    Throwable finishInvocation(Throwable originalFailure) {
        if (cleanupFinished) return originalFailure;
        beginDraining();
        var hardDeadline = cleanupDeadlineNanos();
        Throwable failure = null;
        if (Thread.interrupted()) cleanupInterrupted.set(true);
        try {
            if (cleanupInterrupted.get() && !getActiveExecutorTasks().isEmpty())
                throw new InterruptedException("Invocation caller was interrupted");
            stopCheckpointContinuationsUntil(hardDeadline);
            failure = drainInvocationTasks(originalFailure, hardDeadline);
        } catch (InterruptedException interrupted) {
            cleanupInterrupted.set(true);
            failure = lifecycleFailure("Interrupted while draining invocation tasks", interrupted);
            signalLifecycleFailure(failure);
            failure = settleInterruptedCleanup(hardDeadline, true, failure);
        } catch (RuntimeException problem) {
            failure = problem;
            signalLifecycleFailure(failure);
        } finally {
            failure = finishActualInvocationCleanup(failure);
            cleanupFinished = true;
            restoreCleanupInterrupt();
        }
        var recordedFailure = lifecycleFailure.get();
        var selectedFailure = isFatal(originalFailure) || recordedFailure == null ? originalFailure : recordedFailure;
        if (failure == null) return selectedFailure;
        return isFatal(selectedFailure)
                ? combineFailures(selectedFailure, failure)
                : combineFailures(failure, selectedFailure);
    }

    private Throwable drainInvocationTasks(Throwable originalFailure, long hardDeadline) throws InterruptedException {
        var taskDeadline = Math.max(System.nanoTime(), hardDeadline - CHECKPOINT_CLEANUP_RESERVE.toNanos());
        var recordedFailure = lifecycleFailure.get();
        var abortFailure = isFatal(originalFailure)
                ? originalFailure
                : recordedFailure != null
                        ? recordedFailure
                        : isLifecycleFailure(originalFailure) ? originalFailure : null;
        var retrying = abortFailure != null;
        if (retrying) signalLifecycleFailure(abortFailure);
        else if (!awaitTasksUntil(minDeadline(taskDeadline, MAX_GRACEFUL_DRAIN_DURATION), false)) {
            var failure = lifecycleFailure("Invocation user work exceeded its graceful cleanup budget", null);
            signalLifecycleFailure(failure);
            awaitTasksUntil(minDeadline(taskDeadline, MAX_CANCELLATION_DRAIN_DURATION), true);
            return failure;
        }
        // The root body and operation work have exited. Let response preparation and End finish on their owner,
        // within the remaining overall cleanup budget rather than charging an exporter to the user-work grace cap.
        if (awaitTasksUntil(retrying ? minDeadline(taskDeadline, MAX_CANCELLATION_DRAIN_DURATION) : taskDeadline, true))
            return null;
        var failure = lifecycleFailure("Invocation lifecycle did not exit before the cleanup deadline", null);
        signalLifecycleFailure(failure);
        return failure;
    }

    private boolean awaitTasksUntil(long deadline, boolean includeRootLifecycle) throws InterruptedException {
        while (true) {
            List<ExecutorTaskHandle<?>> tasks;
            synchronized (admissionLock) {
                tasks = getActiveExecutorTasks().stream()
                        .filter(task -> includeRootLifecycle
                                || task.role() != ExecutorTaskHandle.Role.ROOT
                                || !rootHandlerExited)
                        .toList();
                if (tasks.isEmpty()) {
                    // The final empty snapshot and admission closure are one transition. The internal, awaited
                    // EXECUTION-result path remains available; ordinary operations cannot enter after this point.
                    lifecycleState = LifecycleState.CLOSED;
                    return true;
                }
            }
            var remaining = remainingNanos(deadline);
            if (remaining == 0) return false;
            try {
                var exited = CompletableFuture.allOf(
                        tasks.stream().map(ExecutorTaskHandle::exit).toArray(CompletableFuture[]::new));
                var observed = !includeRootLifecycle
                                && tasks.stream().anyMatch(task -> task.role() == ExecutorTaskHandle.Role.ROOT)
                        ? CompletableFuture.anyOf(exited, rootHandlerExitSignal)
                        : exited;
                observed.get(remaining, TimeUnit.NANOSECONDS);
            } catch (ExecutionException impossible) {
                throw new IllegalStateException("Executor task exit signal failed", impossible.getCause());
            } catch (TimeoutException timeout) {
                return false;
            }
        }
    }

    private void stopCheckpointContinuationsUntil(long deadline) throws InterruptedException {
        List<BaseDurableOperation> owners;
        synchronized (activeThreads) {
            closing = true;
            owners = new ArrayList<>(checkpointContinuations.values());
        }
        owners.forEach(ExecutionManager::stopContinuationOwner);
        synchronized (activeThreads) {
            while (!checkpointContinuations.isEmpty()) {
                var remaining = remainingNanos(deadline);
                if (remaining == 0)
                    throw lifecycleFailure(
                            "Checkpoint continuations exceeded the cleanup deadline", new TimeoutException());
                TimeUnit.NANOSECONDS.timedWait(activeThreads, remaining);
            }
        }
    }

    private Throwable settleInterruptedCleanup(long deadline, boolean includeRoot, Throwable primary) {
        while (remainingNanos(deadline) > 0) {
            try {
                stopCheckpointContinuationsUntil(deadline);
                if (!awaitTasksUntil(deadline, includeRoot))
                    return combineFailures(
                            primary,
                            lifecycleFailure(
                                    "Interrupted cleanup exhausted its remaining budget", new TimeoutException()));
                return primary;
            } catch (InterruptedException interrupted) {
                cleanupInterrupted.set(true);
                // Keep the first interruption/failure, but continue best-effort joins with the remaining budget.
            } catch (RuntimeException failure) {
                return combineFailures(primary, failure);
            }
        }
        return primary;
    }

    private void restoreCleanupInterrupt() {
        if (cleanupInterrupted.get()) Thread.currentThread().interrupt();
        cleanupInterrupted.remove();
    }

    private void signalLifecycleFailure(Throwable failure) {
        if (failure instanceof UnrecoverableDurableExecutionException problem
                && LIFECYCLE_ERROR_TYPE.equals(problem.getErrorObject().errorType()))
            lifecycleFailure.compareAndSet(null, problem);
        // Interruption must precede arbitrary inline completion observers.
        var current = currentExecutorTask.get();
        getActiveExecutorTasks().stream().filter(task -> task != current).forEach(task -> task.cancel(true));
        executionExceptionFuture.completeExceptionally(failure);
        stopAllOperations(failure);
    }

    <T> InvocationOutcome<T> awaitInvocationOutcome(CompletableFuture<T> future) {
        try {
            var value = deadlineNanos == null
                    ? future.get()
                    : future.get(
                            remainingNanos(deadlineNanos - EXECUTION_DEADLINE_HEADROOM.toNanos()),
                            TimeUnit.NANOSECONDS);
            return new InvocationOutcome<>(value, null);
        } catch (InterruptedException interrupted) {
            cleanupInterrupted.set(true);
            var failure = lifecycleFailure("Invocation runtime thread was interrupted", interrupted);
            lifecycleFailure.compareAndSet(null, failure);
            return new InvocationOutcome<>(null, failure);
        } catch (TimeoutException timeout) {
            var failure = lifecycleFailure("Invocation deadline reached before execution completed", timeout);
            lifecycleFailure.compareAndSet(null, failure);
            return new InvocationOutcome<>(null, failure);
        } catch (ExecutionException failure) {
            return new InvocationOutcome<>(null, ExceptionHelper.unwrapInvocationFailure(failure.getCause()));
        }
    }

    private long cleanupDeadlineNanos() {
        var localDeadline = addToNow(MAX_CLEANUP_DURATION);
        if (deadlineNanos == null) {
            return localDeadline;
        }
        return Math.min(localDeadline, deadlineNanos - RESPONSE_HEADROOM.toNanos());
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
        if (primary == null) return additional;
        if (additional == null || primary == additional) return primary;
        if (isFatal(additional) && !isFatal(primary)) {
            additional.addSuppressed(primary);
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    @SuppressWarnings("removal")
    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    static boolean isLifecycleFailure(Throwable failure) {
        return failure instanceof UnrecoverableDurableExecutionException control
                && LIFECYCLE_ERROR_TYPE.equals(control.getErrorObject().errorType());
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

    /** Cutoffs request cancellation; actual owner, continuation, and transport completion remain mandatory. */
    private Throwable finishActualInvocationCleanup(Throwable priorFailure) {
        var drain = new DrainFailure(true);
        drain.run(() -> stopCheckpointContinuations(drain));
        drain.run(() -> awaitActualInvocationTasks(drain));
        drain.run(() -> closeLifecycle(drain));
        drain.run(checkpointManager::shutdown);
        try {
            drain.finish();
        } catch (Throwable failure) {
            // Keep shutdown's transport wrapper: close can run while its fatal cause already escapes the body.
            return combineFailures(priorFailure, failure);
        }
        return priorFailure;
    }

    private void awaitActualInvocationTasks(DrainFailure drain) {
        while (true) {
            List<ExecutorTaskHandle<?>> tasks;
            synchronized (admissionLock) {
                tasks = getActiveExecutorTasks();
                if (tasks.isEmpty()) {
                    lifecycleState = LifecycleState.CLOSED;
                    return;
                }
            }
            for (var task : tasks) awaitTaskExit(task, drain);
        }
    }
}
