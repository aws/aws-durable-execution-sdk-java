package com.amazonaws.lambda.durable.operation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Phaser;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.SerDes;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepOptions;

public class StepOperation<T> implements DurableOperation<T> {

    private static final Logger logger = LoggerFactory.getLogger(StepOperation.class);

    private final String operationId;
    private final String name;
    private final Supplier<T> function;
    private final Class<T> resultType;
    private final StepConfig config;
    private final ExecutionManager executionManager;
    private final SerDes serDes;

    public StepOperation(
            String operationId,
            String name,
            Supplier<T> function,
            Class<T> resultType,
            StepConfig config,
            ExecutionManager executionManager,
            SerDes serDes) {
        this.operationId = operationId;
        this.name = name;
        this.function = function;
        this.resultType = resultType;
        this.config = config;
        this.executionManager = executionManager;
        this.serDes = serDes;
    }

    @Override
    public String getOperationId() {
        return operationId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Phaser getPhaser() {
        return executionManager.getPhaser(operationId);
    }

    @Override
    public void execute() {
        // Check replay
        var existing = executionManager.getOperation(operationId);

        if (existing != null) {
            switch (existing.status()) {
                case SUCCEEDED, FAILED -> {
                    // Already done, complete phaser immediately
                    getPhaser().arriveAndDeregister();
                    return;
                }
                case STARTED -> {
                    // If the step was already STARTED, and we're using AT_MOST_ONCE_PER_RETRY
                    // semantics, throw an error.
                    throw new RuntimeException(
                            String.format("step '%s' interrupted", name == null ? operationId : name));
                }
                case PENDING -> {
                    // Step is pending retry - setup polling
                    // Create a future that will be completed when step transitions to READY
                    var pendingFuture = new java.util.concurrent.CompletableFuture<Void>();

                    // When future completes, execute the step
                    pendingFuture.thenRun(() -> executeStepLogic(existing.stepDetails().attempt()));

                    // Start polling for PENDING -> READY transition
                    var nextAttemptTime = existing.stepDetails().nextAttemptTimestamp();
                    if (nextAttemptTime == null) {
                        nextAttemptTime = Instant.now().plusSeconds(1);
                    }
                    executionManager.pollForUpdates(operationId, pendingFuture, nextAttemptTime,
                            Duration.ofSeconds(1));
                    return;
                }
                case READY -> {
                    // Execute with current attempt
                    executeStepLogic(existing.stepDetails().attempt());
                    return;
                }
                default -> {
                    getPhaser().arriveAndDeregister();
                    return;
                }
            }
        } else {
            // First execution
            executeStepLogic(0);
        }
    }

    private void executeStepLogic(int attempt) {
        // Register step thread as active
        String stepThreadId = operationId + "-step";
        executionManager.registerActiveThread(stepThreadId, ThreadType.STEP);

        // Execute in managed executor
        executionManager.getManagedExecutor().execute(() -> {
            try {
                // Check if we need to send START
                var existing = executionManager.getOperation(operationId);
                if (existing == null || existing.status() != OperationStatus.STARTED) {
                    var startUpdate = OperationUpdate.builder()
                            .id(operationId)
                            .name(name)
                            .parentId(null)
                            .type(OperationType.STEP)
                            .action(OperationAction.START)
                            .build();
                    executionManager.sendOperationUpdate(startUpdate).join();
                }

                // Execute the function
                T result = function.get();

                // Send SUCCEED
                var successUpdate = OperationUpdate.builder()
                        .id(operationId)
                        .name(name)
                        .parentId(null)
                        .type(OperationType.STEP)
                        .action(OperationAction.SUCCEED)
                        .payload(serDes.serialize(result))
                        .build();
                executionManager.sendOperationUpdate(successUpdate).join();

                // Two-phase completion (critical!)
                getPhaser().arriveAndAwaitAdvance(); // Phase 0 -> 1 (notify waiters)
                getPhaser().arriveAndAwaitAdvance(); // Phase 1 -> 2 (wait for reactivation)

            } catch (Throwable e) {
                handleStepError(e, attempt);
            } finally {
                executionManager.deregisterActiveThread(stepThreadId);
            }
        });
    }

    private void handleStepError(Throwable e, int attempt) {
        var errorObject = ErrorObject.builder()
                .errorType(e.getClass().getSimpleName())
                .errorMessage(e.getMessage())
                .build();

        if (config != null && config.retryStrategy() != null) {
            var retryDecision = config.retryStrategy().makeRetryDecision(e, attempt);

            if (retryDecision.shouldRetry()) {
                // Send RETRY
                var retryUpdate = OperationUpdate.builder()
                        .id(operationId)
                        .name(name)
                        .parentId(null)
                        .type(OperationType.STEP)
                        .action(OperationAction.RETRY)
                        .error(errorObject)
                        .stepOptions(StepOptions.builder()
                                .nextAttemptDelaySeconds(Math.toIntExact(retryDecision.delay().toSeconds()))
                                .build())
                        .build();
                executionManager.sendOperationUpdate(retryUpdate).join();

                // Setup polling for retry
                var pendingFuture = new CompletableFuture<Void>();
                pendingFuture.thenRun(() -> executeStepLogic(attempt + 1));

                var nextAttemptTime = Instant.now()
                        .plus(retryDecision.delay())
                        .plusMillis(25);
                executionManager.pollForUpdates(operationId, pendingFuture, nextAttemptTime,
                        Duration.ofMillis(200));
                return;
            } else {
                // Send FAIL - retries exhausted
                var failUpdate = OperationUpdate.builder()
                        .id(operationId)
                        .name(name)
                        .parentId(null)
                        .type(OperationType.STEP)
                        .action(OperationAction.FAIL)
                        .error(errorObject)
                        .build();
                executionManager.sendOperationUpdate(failUpdate).join();

                // Complete phaser for failed step
                getPhaser().arriveAndAwaitAdvance();
                getPhaser().arriveAndAwaitAdvance();
            }
        } else {
            // No retry - send FAIL
            var failUpdate = OperationUpdate.builder()
                    .id(operationId)
                    .name(name)
                    .parentId(null)
                    .type(OperationType.STEP)
                    .action(OperationAction.FAIL)
                    .error(errorObject)
                    .build();
            executionManager.sendOperationUpdate(failUpdate).join();

            // Complete phaser for failed step
            getPhaser().arriveAndAwaitAdvance();
            getPhaser().arriveAndAwaitAdvance();
        }
    }

    @Override
    public T get() {
        if (getPhaser().getPhase() == 0) {
            // Operation not done yet
            getPhaser().register();

            // Deregister current thread - allows suspension
            executionManager.deregisterActiveThread("Root");

            // Block until operation completes
            getPhaser().arriveAndAwaitAdvance(); // Wait for phase 0

            // Reactivate current thread
            executionManager.registerActiveThread("Root", ThreadType.CONTEXT);

            // Complete phase 1
            getPhaser().arriveAndDeregister();
        }

        // Get result from coordinator
        Operation op = executionManager.getOperation(operationId);
        if (op == null) {
            throw new RuntimeException("Step '" + name + "' operation not found");
        }

        if (op.status() == OperationStatus.SUCCEEDED) {
            var stepDetails = op.stepDetails();
            String result = (stepDetails != null) ? stepDetails.result() : null;
            return serDes.deserialize(result, resultType);
        } else if (op.status() == OperationStatus.FAILED) {
            throw new RuntimeException("Step '" + name + "' failed");
        } else {
            throw new RuntimeException("Step '" + name + "' in unexpected status: " + op.status());
        }
    }
}
