// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.CompletionConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Abstract base class for concurrent operations (map, parallel).
 *
 * <p>Provides the shared concurrent execution framework: root child context creation, queue-based concurrency limiting,
 * success/failure tracking, completion criteria evaluation, and thread registration ordering.
 *
 * <p>Subclasses implement {@link #startBranches()} to create branches via {@link #branchInternal} and
 * {@link #aggregateResults()} to collect branch results into the final result type.
 *
 * @param <R> the aggregate result type (e.g., {@code MapResult<O>})
 */
public abstract class BaseConcurrentOperation<R> extends BaseDurableOperation<R> {

    private static final Logger logger = LoggerFactory.getLogger(BaseConcurrentOperation.class);
    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    // All mutable state below is guarded by `lock`.
    protected final Object lock = new Object();
    private final List<ChildContextOperation<?>> branches = new ArrayList<>();
    private final Queue<ChildContextOperation<?>> pendingQueue = new LinkedList<>();
    private final Set<ChildContextOperation<?>> startedBranches = ConcurrentHashMap.newKeySet();
    private int activeBranches;
    private int succeeded;
    private int failed;
    private CompletionReason completionReason;
    private boolean earlyTermination;
    private final Set<String> completedBranchIds = new HashSet<>();

    private final Integer maxConcurrency;
    private final CompletionConfig completionConfig;
    private final OperationSubType subType;
    private DurableContextImpl rootContext;
    private OperationIdGenerator operationIdGenerator;

    protected BaseConcurrentOperation(
            String operationId,
            String name,
            OperationSubType subType,
            Integer maxConcurrency,
            CompletionConfig completionConfig,
            TypeToken<R> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext) {
        super(
                OperationIdentifier.of(operationId, name, OperationType.CONTEXT, subType),
                resultTypeToken,
                resultSerDes,
                durableContext);
        this.subType = subType;
        this.maxConcurrency = maxConcurrency;
        this.completionConfig = completionConfig;
    }

    // ========== lifecycle ==========

    @Override
    protected void start() {
        sendOperationUpdateAsync(
                OperationUpdate.builder().action(OperationAction.START).subType(subType.getValue()));
        var parentThreadContext = getCurrentThreadContext();
        this.rootContext = getContext().createChildContext(getOperationId(), getName());
        this.operationIdGenerator = new OperationIdGenerator(getOperationId());
        startBranches();
        processAlreadyCompletedBranches();
        getContext().getExecutionManager().setCurrentThreadContext(parentThreadContext);
    }

    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED -> {
                if (existing.contextDetails() != null
                        && Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    var parentThreadContext = getCurrentThreadContext();
                    this.rootContext = getContext().createChildContext(getOperationId(), getName());
                    this.operationIdGenerator = new OperationIdGenerator(getOperationId());
                    startBranches();
                    processAlreadyCompletedBranches();
                    getContext().getExecutionManager().setCurrentThreadContext(parentThreadContext);
                } else {
                    markAlreadyCompleted();
                }
            }
            case STARTED -> {
                var parentThreadContext = getCurrentThreadContext();
                this.rootContext = getContext().createChildContext(getOperationId(), getName());
                this.operationIdGenerator = new OperationIdGenerator(getOperationId());
                startBranches();
                processAlreadyCompletedBranches();
                getContext().getExecutionManager().setCurrentThreadContext(parentThreadContext);
            }
            default ->
                terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected concurrent operation status: " + existing.status());
        }
    }

    // ========== abstract methods for subclasses ==========

    protected abstract void startBranches();

    protected abstract R aggregateResults();

    // ========== post-startBranches processing ==========

    /**
     * Processes branches that completed synchronously during {@code startBranches()} via {@code markAlreadyCompleted()}
     * (replay of SUCCEEDED/FAILED branches). These branches don't go through {@code executeChildContext()}, so their
     * {@code onCompleteCallback} doesn't fire. We process them here, after all branches have been created, to avoid
     * re-entrant issues during {@code startBranches()}.
     */
    private void processAlreadyCompletedBranches() {
        // Take a snapshot of branches that are already completed
        List<ChildContextOperation<?>> alreadyCompleted;
        synchronized (lock) {
            alreadyCompleted =
                    branches.stream().filter(b -> b.completionFuture.isDone()).toList();
        }

        for (var branch : alreadyCompleted) {
            var op = branch.getOperation();
            if (op != null) {
                boolean success = op.status() == OperationStatus.SUCCEEDED;
                onChildContextComplete(branch, success);
            }
        }
    }

    // ========== branch creation ==========

    protected <T> ChildContextOperation<T> branchInternal(
            String branchName,
            OperationSubType branchSubType,
            TypeToken<T> typeToken,
            SerDes serDes,
            Function<DurableContext, T> function) {
        var branchOpId = operationIdGenerator.nextOperationId();
        var branch = new ChildContextOperation<>(
                OperationIdentifier.of(branchOpId, branchName, OperationType.CONTEXT, branchSubType),
                function,
                typeToken,
                serDes,
                rootContext);

        // Only use onCompleteCallback (finally block of executeChildContext) for branches that
        // actually execute. For branches that replay as SUCCEEDED/FAILED via markAlreadyCompleted(),
        // we process them after startBranches() completes — see processCompletedBranches().
        branch.setOnCompleteCallback(() -> {
            var op = branch.getOperation();
            if (op != null && ExecutionManager.isTerminalStatus(op.status())) {
                boolean success = op.status() == OperationStatus.SUCCEEDED;
                onChildContextComplete(branch, success);
            } else {
                // Branch suspended (op is null or STARTED) — decrement active count.
                onChildContextSuspended();
            }
        });

        boolean shouldStart;
        synchronized (lock) {
            branches.add(branch);
            if (!earlyTermination && (maxConcurrency == null || activeBranches < maxConcurrency)) {
                activeBranches++;
                shouldStart = true;
            } else {
                pendingQueue.add(branch);
                shouldStart = false;
            }
        }

        if (shouldStart) {
            startedBranches.add(branch);
            branch.execute();
        }
        return branch;
    }

    // ========== completion callback ==========

    /**
     * Called on the checkpoint processing thread when a branch's completionFuture completes. Updates counters,
     * evaluates completion criteria, and either starts the next queued branch or finalizes the operation.
     *
     * <p>When all branches have completed (or early termination criteria are met with no pending/active branches), this
     * method aggregates results, checkpoints the parent operation, and completes the parent's {@code completionFuture}
     * — allowing {@code get()} to unblock via {@code waitForOperationCompletion()}.
     *
     * <p>All mutable state updates are synchronized via {@code lock} to prevent races with {@link #branchInternal}
     * (which runs on the parent context thread) and with other concurrent completion callbacks.
     */
    protected void onChildContextComplete(ChildContextOperation<?> branch, boolean success) {
        ChildContextOperation<?> nextToStart = null;
        boolean shouldFinalize = false;

        synchronized (lock) {
            // Idempotency: both onCompleteCallback (finally block) and completionFuture.thenRun
            // may fire for the same branch. Skip if already counted.
            if (!completedBranchIds.add(branch.getOperationId())) {
                return;
            }

            if (success) {
                succeeded++;
            } else {
                failed++;
            }

            // Evaluate completion criteria
            if (!earlyTermination && shouldTerminateEarly()) {
                earlyTermination = true;
                completionReason = evaluateCompletionReason();
                logger.trace(
                        "Early termination triggered for operation {}: reason={}", getOperationId(), completionReason);
            }

            // Start next queued branch with correct thread ordering:
            // register new branch thread BEFORE deregistering completed branch thread
            if (!earlyTermination) {
                nextToStart = pendingQueue.poll();
                if (nextToStart == null) {
                    activeBranches--;
                }
                // else activeBranches stays the same (one completing, one starting)
            } else {
                activeBranches--;
            }

            // Check if all work is done: no active branches and either no pending branches
            // or early termination (pending branches won't be started)
            if (activeBranches == 0 && (pendingQueue.isEmpty() || earlyTermination)) {
                shouldFinalize = true;
                if (completionReason == null) {
                    completionReason = CompletionReason.ALL_COMPLETED;
                }
            }
        }

        // Execute outside the lock — branch.execute() submits to the executor and may trigger
        // further callbacks; holding the lock here would risk deadlock.
        if (nextToStart != null) {
            startedBranches.add(nextToStart);
            nextToStart.execute();
        }

        // Finalize outside the lock — checkpointing is blocking I/O.
        if (shouldFinalize) {
            R result = aggregateResults();
            checkpointResult(result);
        }
        // completed branch's thread is deregistered by ChildContextOperation's close() in BaseContext
    }

    /**
     * Called when a branch suspends (e.g., due to a wait() operation). Decrements the active branch count so that the
     * finalization check in {@link #onChildContextComplete} can detect when all branches are either completed or
     * suspended. Does NOT start the next queued branch — suspended branches will resume on re-invocation.
     */
    private void onChildContextSuspended() {
        synchronized (lock) {
            activeBranches--;
            // Don't start next branch — this branch suspended, it will resume on re-invocation.
            // Don't finalize — suspended branches haven't produced results yet.
        }
    }

    // ========== completion evaluation ==========

    /** Must be called while holding {@code lock}. */
    private boolean shouldTerminateEarly() {
        // Check minSuccessful
        if (completionConfig.minSuccessful() != null && succeeded >= completionConfig.minSuccessful()) {
            return true;
        }

        // Check toleratedFailureCount
        if (completionConfig.toleratedFailureCount() != null && failed > completionConfig.toleratedFailureCount()) {
            return true;
        }

        // Check toleratedFailurePercentage
        int totalCompleted = succeeded + failed;
        if (completionConfig.toleratedFailurePercentage() != null
                && totalCompleted > 0
                && ((double) failed / totalCompleted) > completionConfig.toleratedFailurePercentage()) {
            return true;
        }

        return false;
    }

    /** Must be called while holding {@code lock}. */
    protected CompletionReason evaluateCompletionReason() {
        if (completionConfig.minSuccessful() != null && succeeded >= completionConfig.minSuccessful()) {
            return CompletionReason.MIN_SUCCESSFUL_REACHED;
        }
        return CompletionReason.FAILURE_TOLERANCE_EXCEEDED;
    }

    /**
     * Checkpoints the parent concurrent operation as SUCCEEDED. Uses synchronous {@code sendOperationUpdate} because
     * this is called from the context thread in {@code get()}, where it is safe to block.
     *
     * <p>Small results (&lt;256KB) are checkpointed directly as payload. Large results are checkpointed with
     * {@code replayChildren=true} and an empty payload, so on replay the result is reconstructed from child contexts.
     */
    protected void checkpointResult(R result) {
        var serialized = serializeResult(result);
        var serializedBytes = serialized.getBytes(StandardCharsets.UTF_8);

        if (serializedBytes.length < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(subType.getValue())
                    .payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + replayChildren flag
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .subType(subType.getValue())
                    .payload("")
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    // ========== get ==========

    @Override
    public R get() {
        var executionManager = getContext().getExecutionManager();
        var threadContext = getCurrentThreadContext();

        synchronized (completionFuture) {
            if (!isOperationCompleted()) {
                completionFuture.thenRun(() -> registerActiveThread(threadContext.threadId()));
                executionManager.deregisterActiveThread(threadContext.threadId());
            }
        }

        // Race completionFuture against executionExceptionFuture.
        // If branches suspend, executionExceptionFuture completes with SuspendExecutionException,
        // which propagates up through the handler to DurableExecutor → returns PENDING.
        // If branches complete, completionFuture completes and we proceed to read the result.
        executionManager.runUntilCompleteOrSuspend(completionFuture).join();

        var op = getOperation();

        if (op.status() == OperationStatus.SUCCEEDED) {
            if (op.contextDetails() != null
                    && Boolean.TRUE.equals(op.contextDetails().replayChildren())) {
                return aggregateResults();
            }
            var contextDetails = op.contextDetails();
            var result = (contextDetails != null) ? contextDetails.result() : null;
            return deserializeResult(result);
        } else {
            return terminateExecutionWithIllegalDurableOperationException(
                    "Unexpected operation status after completion: " + op.status());
        }
    }

    // ========== protected accessors for subclasses ==========

    protected List<ChildContextOperation<?>> getBranches() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(branches));
        }
    }

    protected CompletionReason getCompletionReason() {
        synchronized (lock) {
            return completionReason;
        }
    }

    protected int getSucceeded() {
        synchronized (lock) {
            return succeeded;
        }
    }

    protected int getFailed() {
        synchronized (lock) {
            return failed;
        }
    }

    protected boolean isEarlyTermination() {
        synchronized (lock) {
            return earlyTermination;
        }
    }

    protected DurableContext getRootContext() {
        return rootContext;
    }

    /** Returns a snapshot of the pending queue of branches that have not yet been started. */
    protected Queue<ChildContextOperation<?>> getPendingQueue() {
        synchronized (lock) {
            return new LinkedList<>(pendingQueue);
        }
    }

    /** Returns the set of branches that have been started (had execute() called). */
    protected Set<ChildContextOperation<?>> getStartedBranches() {
        return startedBranches;
    }
}
