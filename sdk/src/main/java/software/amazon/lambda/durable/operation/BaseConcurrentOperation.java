// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final List<ChildContextOperation<?>> branches = new ArrayList<>();
    private final Queue<ChildContextOperation<?>> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Set<ChildContextOperation<?>> startedBranches = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeBranches = new AtomicInteger(0);
    private final AtomicInteger succeeded = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final Integer maxConcurrency;
    private final CompletionConfig completionConfig;
    private final OperationSubType subType;
    private volatile CompletionReason completionReason;
    private volatile boolean earlyTermination = false;
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
        this.rootContext = getContext().createChildContext(getOperationId(), getName());
        this.operationIdGenerator = new OperationIdGenerator(getOperationId());
        startBranches();
    }

    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED -> {
                if (existing.contextDetails() != null
                        && Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    // Large result: reconstruct by replaying child contexts
                    this.rootContext = getContext().createChildContext(getOperationId(), getName());
                    this.operationIdGenerator = new OperationIdGenerator(getOperationId());
                    startBranches();
                } else {
                    markAlreadyCompleted();
                }
            }
            case FAILED -> markAlreadyCompleted();
            case STARTED -> {
                // Interrupted mid-execution: resume from last checkpoint
                this.rootContext = getContext().createChildContext(getOperationId(), getName());
                this.operationIdGenerator = new OperationIdGenerator(getOperationId());
                startBranches();
            }
            default ->
                terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected concurrent operation status: " + existing.status());
        }
    }

    // ========== abstract methods for subclasses ==========

    protected abstract void startBranches();

    protected abstract R aggregateResults();

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
        branches.add(branch);

        // Attach callback BEFORE execution starts (or before future can complete).
        // The thenRun runs synchronously inside the synchronized(completionFuture) block
        // when completionFuture.complete(null) is called, so it executes on the checkpoint
        // processing thread. This callback only does lightweight work: update counters,
        // evaluate CompletionConfig, dequeue and start next branch.
        branch.completionFuture.thenRun(() -> {
            var op = branch.getOperation();
            boolean success = op != null && op.status() == OperationStatus.SUCCEEDED;
            onChildContextComplete(branch, success);
        });

        if (!earlyTermination && (maxConcurrency == null || activeBranches.get() < maxConcurrency)) {
            activeBranches.incrementAndGet();
            startedBranches.add(branch);
            branch.execute();
        } else {
            pendingQueue.add(branch);
        }
        return branch;
    }

    // ========== completion callback ==========

    /**
     * Called on the checkpoint processing thread when a branch's completionFuture completes. Only does lightweight
     * work: update counters, evaluate CompletionConfig, dequeue and start next branch. Does NOT call
     * finalizeOperation() or checkpointResult() — those happen in get() on the context thread.
     */
    protected void onChildContextComplete(ChildContextOperation<?> branch, boolean success) {
        if (success) {
            succeeded.incrementAndGet();
        } else {
            failed.incrementAndGet();
        }

        // Evaluate completion criteria
        if (!earlyTermination && shouldTerminateEarly()) {
            earlyTermination = true;
            completionReason = evaluateCompletionReason();
            logger.trace("Early termination triggered for operation {}: reason={}", getOperationId(), completionReason);
        }

        // Start next queued branch with correct thread ordering:
        // register new branch thread BEFORE deregistering completed branch thread
        if (!earlyTermination) {
            var next = pendingQueue.poll();
            if (next != null) {
                // activeBranches stays the same (one completing, one starting)
                startedBranches.add(next);
                next.execute(); // registers new thread internally via ChildContextOperation.start()
            } else {
                activeBranches.decrementAndGet();
            }
        } else {
            activeBranches.decrementAndGet();
        }
        // completed branch's thread is deregistered by ChildContextOperation's close() in BaseContext
    }

    // ========== completion evaluation ==========

    private boolean shouldTerminateEarly() {
        // Check minSuccessful
        if (completionConfig.minSuccessful() != null && succeeded.get() >= completionConfig.minSuccessful()) {
            return true;
        }

        // Check toleratedFailureCount
        if (completionConfig.toleratedFailureCount() != null
                && failed.get() > completionConfig.toleratedFailureCount()) {
            return true;
        }

        // Check toleratedFailurePercentage
        int totalCompleted = succeeded.get() + failed.get();
        if (completionConfig.toleratedFailurePercentage() != null
                && totalCompleted > 0
                && ((double) failed.get() / totalCompleted) > completionConfig.toleratedFailurePercentage()) {
            return true;
        }

        return false;
    }

    protected CompletionReason evaluateCompletionReason() {
        if (completionConfig.minSuccessful() != null && succeeded.get() >= completionConfig.minSuccessful()) {
            return CompletionReason.MIN_SUCCESSFUL_REACHED;
        }
        return CompletionReason.FAILURE_TOLERANCE_EXCEEDED;
    }

    private void finalizeOperation() {
        if (completionReason == null) {
            completionReason = CompletionReason.ALL_COMPLETED;
        }

        R result = aggregateResults();
        checkpointResult(result);
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
        var op = waitForOperationCompletion();

        if (op.status() == OperationStatus.SUCCEEDED) {
            if (op.contextDetails() != null
                    && Boolean.TRUE.equals(op.contextDetails().replayChildren())) {
                // Large result was reconstructed via replay — aggregate from branches
                return aggregateResults();
            }
            var contextDetails = op.contextDetails();
            var result = (contextDetails != null) ? contextDetails.result() : null;
            return deserializeResult(result);
        } else if (op.status() == OperationStatus.FAILED) {
            var contextDetails = op.contextDetails();
            var errorObject = (contextDetails != null) ? contextDetails.error() : null;
            var original = deserializeException(errorObject);
            if (original != null) {
                throw new RuntimeException(original);
            }
            throw new RuntimeException("Concurrent operation failed: " + getOperationId());
        } else {
            return terminateExecutionWithIllegalDurableOperationException(
                    "Unexpected operation status after completion: " + op.status());
        }
    }

    // ========== protected accessors for subclasses ==========

    protected List<ChildContextOperation<?>> getBranches() {
        return Collections.unmodifiableList(branches);
    }

    protected CompletionReason getCompletionReason() {
        return completionReason;
    }

    protected AtomicInteger getSucceeded() {
        return succeeded;
    }

    protected AtomicInteger getFailed() {
        return failed;
    }

    protected boolean isEarlyTermination() {
        return earlyTermination;
    }

    protected DurableContext getRootContext() {
        return rootContext;
    }

    /** Returns the pending queue of branches that have not yet been started. */
    protected Queue<ChildContextOperation<?>> getPendingQueue() {
        return pendingQueue;
    }

    /** Returns the set of branches that have been started (had execute() called). */
    protected Set<ChildContextOperation<?>> getStartedBranches() {
        return startedBranches;
    }
}
