// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.StepSemantics;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.StepFailedException;
import com.amazonaws.lambda.durable.exception.StepInterruptedException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ExecutionPhase;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.logging.DurableLogger;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Phaser;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
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
    private final TypeToken<T> resultTypeToken;
    private final StepConfig config;
    private final ExecutionManager executionManager;
    private final DurableLogger durableLogger;
    private final SerDes serDes;
    private final Phaser phaser;

    public StepOperation(
            String operationId,
            String name,
            Supplier<T> function,
            Class<T> resultType,
            StepConfig config,
            ExecutionManager executionManager,
            DurableLogger durableLogger,
            SerDes serDes) {
        this(operationId, name, function, resultType, null, config, executionManager, durableLogger, serDes);
    }

    public StepOperation(
            String operationId,
            String name,
            Supplier<T> function,
            TypeToken<T> resultTypeToken,
            StepConfig config,
            ExecutionManager executionManager,
            DurableLogger durableLogger,
            SerDes serDes) {
        this(operationId, name, function, null, resultTypeToken, config, executionManager, durableLogger, serDes);
    }

    private StepOperation(
            String operationId,
            String name,
            Supplier<T> function,
            Class<T> resultType,
            TypeToken<T> resultTypeToken,
            StepConfig config,
            ExecutionManager executionManager,
            DurableLogger durableLogger,
            SerDes serDes) {
        if (resultType == null && resultTypeToken == null) {
            throw new IllegalArgumentException("Either resultType or resultTypeToken must be provided");
        }
        if (resultType != null && resultTypeToken != null) {
            throw new IllegalArgumentException("Cannot provide both resultType and resultTypeToken");
        }

        this.operationId = operationId;
        this.name = name;
        this.function = function;
        this.resultType = resultType;
        this.resultTypeToken = resultTypeToken;
        this.config = config;
        this.executionManager = executionManager;
        this.durableLogger = durableLogger;
        // Use custom SerDes from config if provided, otherwise use default
        this.serDes = (config != null && config.serDes() != null) ? config.serDes() : serDes;

        this.phaser = executionManager.startPhaser(operationId);
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
    public void execute() {
        var existing = executionManager.getOperationAndUpdateReplayState(operationId);

        if (existing != null) {
            // This means we are in a replay scenario
            switch (existing.status()) {
                case SUCCEEDED, FAILED -> {
                    // Operation is already completed (we are in a replay). We advance and
                    // deregister from the Phaser
                    // so that .get() doesn't block and returns the result immediately. See
                    // StepOperation.get().
                    logger.trace("Detected terminal status during replay. Advancing phaser 0 -> 1 {}.", phaser);
                    phaser.arriveAndDeregister(); // Phase 0 -> 1
                }
                case STARTED -> {
                    var attempt = existing.stepDetails().attempt() != null
                            ? existing.stepDetails().attempt()
                            : 0;
                    if (getSemantics() == StepSemantics.AT_MOST_ONCE_PER_RETRY) {
                        // AT_MOST_ONCE: treat as interrupted, go through retry logic
                        handleInterruptedStep(attempt);
                    } else {
                        // AT_LEAST_ONCE: re-execute the step
                        executeStepLogic(attempt);
                    }
                }
                case PENDING -> {
                    // Step is pending retry - setup polling
                    // Create a future that will be completed when step transitions to READY
                    var pendingFuture = new CompletableFuture<Void>();

                    // When future completes, execute the step
                    pendingFuture.thenRun(
                            () -> executeStepLogic(existing.stepDetails().attempt()));

                    // Start polling for PENDING -> READY transition
                    var nextAttemptTime = existing.stepDetails().nextAttemptTimestamp();
                    if (nextAttemptTime == null) {
                        nextAttemptTime = Instant.now().plusSeconds(1);
                    }
                    executionManager.pollUntilReady(operationId, pendingFuture, nextAttemptTime, Duration.ofSeconds(1));
                }
                case READY -> {
                    // Execute with current attempt
                    executeStepLogic(existing.stepDetails().attempt());
                }
                default ->
                    throw new RuntimeException(String.format("Unrecognized step status '%s'", existing.status()));
            }
        } else {
            // First execution
            executeStepLogic(0);
        }
    }

    private void executeStepLogic(int attempt) {
        // TODO: Modify this logic when child contexts are introduced such that the child context id is in this key
        var stepThreadId = operationId + "-step";

        // Register step thread as active BEFORE executor runs (prevents suspension when handler deregisters)
        // thread local OperationContext is set inside the executor since that's where the step actually runs
        executionManager.registerActiveThread(stepThreadId, ThreadType.STEP);

        // Execute in managed executor
        executionManager.getManagedExecutor().execute(() -> {
            // Set thread local OperationContext on the executor thread
            executionManager.setCurrentContext(stepThreadId, ThreadType.STEP);
            // Set operation context for logging in this thread
            durableLogger.setOperationContext(operationId, name, attempt);
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

                    if (getSemantics() == StepSemantics.AT_MOST_ONCE_PER_RETRY) {
                        // AT_MOST_ONCE: await START checkpoint before executing user code
                        executionManager.sendOperationUpdate(startUpdate).join();
                    } else {
                        // AT_LEAST_ONCE: fire-and-forget START checkpoint
                        executionManager.sendOperationUpdate(startUpdate);
                    }
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
            } catch (Throwable e) {
                handleStepError(e, attempt);
            } finally {
                executionManager.deregisterActiveThread(stepThreadId);
                durableLogger.clearOperationContext();
            }
        });
    }

    private void handleInterruptedStep(int attempt) {
        var error = new StepInterruptedException(operationId, name);
        handleStepFailure(error, attempt + 1);
    }

    private StepSemantics getSemantics() {
        return config != null ? config.semantics() : StepSemantics.AT_LEAST_ONCE_PER_RETRY;
    }

    private void handleStepError(Throwable e, int attempt) {
        handleStepFailure(e, attempt);
    }

    private void handleStepFailure(Throwable error, int attempt) {
        var errorObject = ErrorObject.builder()
                .errorType(error.getClass().getSimpleName())
                .errorMessage(error.getMessage())
                // TODO: Add errorData object once we support polymorphic object mappers
                .stackTrace(StepFailedException.serializeStackTrace(error.getStackTrace()))
                .build();

        if (config != null && config.retryStrategy() != null) {
            var retryDecision = config.retryStrategy().makeRetryDecision(error, attempt);

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
                                // RetryDecisions always produce integer number of seconds greater or equals to
                                // 1 (no sub-second numbers)
                                .nextAttemptDelaySeconds(
                                        Math.toIntExact(retryDecision.delay().toSeconds()))
                                .build())
                        .build();
                executionManager.sendOperationUpdate(retryUpdate).join();

                // Setup polling for retry
                var pendingFuture = new CompletableFuture<Void>();
                pendingFuture.thenRun(() -> executeStepLogic(attempt + 1));

                var nextAttemptTime = Instant.now().plus(retryDecision.delay()).plusMillis(25);
                executionManager.pollUntilReady(operationId, pendingFuture, nextAttemptTime, Duration.ofMillis(200));
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
        }
    }

    @Override
    public T get() {
        // Get current context from ThreadLocal
        var currentContext = executionManager.getCurrentContext();

        // Nested steps are not supported
        if (currentContext.threadType() == ThreadType.STEP) {
            throw new IllegalStateException("Nested step calling is not supported. Cannot call get() on step '" + name
                    + "' from within another step's execution.");
        }

        // If we are in a replay where the operation is already complete (SUCCEEDED /
        // FAILED), the Phaser will be
        // advanced in .execute() already and we don't block but return the result
        // immediately.
        if (phaser.getPhase() == ExecutionPhase.RUNNING.getValue()) {
            // Operation not done yet
            phaser.register();

            // Deregister current context - allows suspension
            logger.debug("StepOperation.get() attempting to deregister context: {}", currentContext.contextId());
            executionManager.deregisterActiveThread(currentContext.contextId());

            // Block until operation completes
            logger.trace("Waiting for operation to finish {} (Phaser: {})", operationId, phaser);
            phaser.arriveAndAwaitAdvance(); // Wait for phase 0

            // Reactivate current context
            executionManager.registerActiveThreadWithContext(currentContext.contextId(), currentContext.threadType());

            // Complete phase 1
            phaser.arriveAndDeregister();
        }

        // Get result from coordinator
        var op = executionManager.getOperation(operationId);
        if (op == null) {
            throw new IllegalStateException("Step '" + name + "' operation not found");
        }

        if (op.status() == OperationStatus.SUCCEEDED) {
            var stepDetails = op.stepDetails();
            var result = (stepDetails != null) ? stepDetails.result() : null;

            // Use TypeToken if provided, otherwise use Class
            if (resultTypeToken != null) {
                return serDes.deserialize(result, resultTypeToken);
            } else {
                return serDes.deserialize(result, resultType);
            }
        } else {
            // It failed so there's some kind of throwable. If we're using a serDes with
            // type info, deserialize and rethrow the original
            // throwable. Otherwise, throw a new StepFailedException that includes info
            // about the original throwable.

            // TODO: Enable this feature after introducing polymorphic object mapper
            // support.
            // String errorData = op.stepDetails().error().errorData();
            // if (errorData != null && serDes.supportsIncludingTypeInfo()) {
            // SneakyThrow.sneakyThrow((Throwable)
            // serDes.deserializeWithTypeInfo(errorData));
            // }

            var errorType = op.stepDetails().error().errorType();

            // Throw StepInterruptedException directly for AT_MOST_ONCE interrupted steps
            // Todo: Change once errorData object is implemented
            if ("StepInterruptedException".equals(errorType)) {
                throw new StepInterruptedException(operationId, name);
            }

            throw new StepFailedException(
                    String.format(
                            "Step failed with error of type %s. Message: %s",
                            errorType, op.stepDetails().error().errorMessage()),
                    null,
                    // Preserve original stack trace
                    StepFailedException.deserializeStackTrace(
                            op.stepDetails().error().stackTrace()));
        }
    }
}
