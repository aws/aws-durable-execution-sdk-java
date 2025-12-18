package com.amazonaws.lambda.durable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.checkpoint.SuspendExecutionException;
import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.util.SneakyThrow;
import com.amazonaws.services.lambda.runtime.Context;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepOptions;
import software.amazon.awssdk.services.lambda.model.WaitOptions;

public class DurableContext {
    private final CheckpointManager checkpointManager;
    private final SerDes serDes;
    private final Context lambdaContext;
    private final AtomicInteger operationCounter;

    DurableContext(CheckpointManager checkpointManager, SerDes serDes, Context lambdaContext) {
        this.checkpointManager = checkpointManager;
        this.serDes = serDes;
        this.lambdaContext = lambdaContext;
        this.operationCounter = new AtomicInteger(0);
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        return step(name, resultType, func,
                StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build());
    }

    public <T> T step(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        // Simply delegate to stepAsync and block on the result
        try {
            return stepAsync(name, resultType, func, config).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SneakyThrow.sneakyThrow(e);
            return null; // unreachable
        } catch (ExecutionException e) {
            // Unwrap the cause and rethrow it using SneakyThrow to preserve original
            // exception type
            Throwable cause = e.getCause();
            SneakyThrow.sneakyThrow(cause);
            return null; // unreachable
        }
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(name, resultType, func,
                StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build());
    }

    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func, StepConfig config) {
        var operationId = nextOperationId();

        // Check replay through checkpoint manager
        var existing = checkpointManager.getOperation(operationId);

        // Validate replay consistency
        if (existing.isPresent()) {
            validateReplay(operationId, OperationType.STEP, name, existing.get());
        }

        if (existing.isPresent()) {
            switch (existing.get().status()) {
                case SUCCEEDED -> {
                    var stepDetails = existing.get().stepDetails();
                    String result = (stepDetails != null) ? stepDetails.result() : null;
                    return new DurableFuture<>(CompletableFuture.completedFuture(
                            serDes.deserialize(result, resultType)));
                }
                case FAILED -> {
                    // Step already failed, return failed future
                    var failedFuture = new CompletableFuture<T>();
                    failedFuture.completeExceptionally(new RuntimeException("Step '" + name + "' already failed"));
                    return new DurableFuture<>(failedFuture);
                }
                case STARTED -> {
                    // Step was already started but not completed
                    var failedFuture = new CompletableFuture<T>();
                    failedFuture.completeExceptionally(new RuntimeException("Step '" + name + "' was interrupted"));
                    return new DurableFuture<>(failedFuture);
                }
                case PENDING -> {
                    // Step is pending retry - return suspended future
                    var suspendedFuture = new CompletableFuture<T>();
                    suspendedFuture.completeExceptionally(new SuspendExecutionException());
                    return new DurableFuture<>(suspendedFuture);
                }
                case READY -> {
                    // Execute async with current attempt number
                    var stepDetails = existing.get().stepDetails();
                    return executeStep(operationId, name, func, config, stepDetails.attempt());
                }
                default -> {
                    var failedFuture = new CompletableFuture<T>();
                    failedFuture.completeExceptionally(
                            new RuntimeException("Unrecognized step status: " + existing.get().status()));
                    return new DurableFuture<>(failedFuture);
                }
            }
        } else {
            // First time executing this step
            return executeStep(operationId, name, func, config, 0);
        }
    }

    public void wait(Duration duration) {
        wait(null, duration);
    }

    public void wait(String waitName, Duration duration) {
        var operationId = nextOperationId();

        // Check replay through checkpoint manager
        var existing = checkpointManager.getOperation(operationId);

        // Validate replay consistency
        if (existing.isPresent()) {
            validateReplay(operationId, OperationType.WAIT, waitName, existing.get());
        }

        if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
            return; // Wait already completed
        }

        // Send wait operation update
        var update = OperationUpdate.builder()
                .id(operationId)
                .name(waitName)
                .parentId(null) // Level 1 conformance - no nested contexts
                .type(OperationType.WAIT)
                .action(OperationAction.START)
                .waitOptions(WaitOptions.builder()
                        .waitSeconds((int) duration.toSeconds())
                        .build())
                .build();
        checkpointManager.checkpoint(update).join();
        throw new SuspendExecutionException();
    }

    public Context getLambdaContext() {
        return lambdaContext;
    }

    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }

    /**
     * Validates that current operation matches checkpointed operation during
     * replay.
     */
    private void validateReplay(String operationId, OperationType expectedType, String expectedName,
            Operation checkpointed) {
        if (checkpointed == null || checkpointed.type() == null) {
            return; // First execution, no validation needed
        }

        if (!checkpointed.type().equals(expectedType)) {
            throw new NonDeterministicExecutionException(
                    String.format("Operation type mismatch for \"%s\". Expected %s, got %s",
                            operationId, checkpointed.type(), expectedType));
        }

        if (!Objects.equals(checkpointed.name(), expectedName)) {
            throw new NonDeterministicExecutionException(
                    String.format("Operation name mismatch for \"%s\". Expected \"%s\", got \"%s\"",
                            operationId, checkpointed.name(), expectedName));
        }
    }

    /**
     * Executes a step operation asynchronously.
     * Sends START action, executes the function, then sends SUCCEED/RETRY/FAIL
     * based on result.
     */
    private <T> DurableFuture<T> executeStep(String operationId, String stepName, Supplier<T> func, StepConfig config,
            int currentAttempt) {
        // Execute async
        var future = CompletableFuture.supplyAsync(() -> {
            // Check if we need to send START action (only for new operations, not READY
            // status)
            var existing = checkpointManager.getOperation(operationId);
            if (existing.isEmpty() || existing.get().status() != OperationStatus.STARTED) {
                // Send START action for new operations
                var startUpdate = OperationUpdate.builder()
                        .id(operationId)
                        .name(stepName)
                        .parentId(null) // Level 1 conformance - no nested contexts
                        .type(OperationType.STEP)
                        .action(OperationAction.START)
                        .build();
                checkpointManager.checkpoint(startUpdate).join();
            }

            try {
                // Execute the step function
                T result = func.get();

                // Send SUCCEED action with result
                var successUpdate = OperationUpdate.builder()
                        .id(operationId)
                        .name(stepName)
                        .parentId(null) // Level 1 conformance - no nested contexts
                        .type(OperationType.STEP)
                        .action(OperationAction.SUCCEED)
                        .payload(serDes.serialize(result))
                        .build();
                checkpointManager.checkpoint(successUpdate).join();

                return result;
            } catch (Throwable e) {
                // Handle retry logic based on StepConfig
                if (config != null && config.retryStrategy() != null) {
                    var retryDecision = config.retryStrategy().makeRetryDecision(e, currentAttempt);

                    var errorObject = ErrorObject.builder()
                            .errorType(e.getClass().getSimpleName())
                            .errorMessage(e.getMessage())
                            .build();

                    if (retryDecision.shouldRetry()) {
                        // Send RETRY action with delay
                        var retryUpdate = OperationUpdate.builder()
                                .id(operationId)
                                .name(stepName)
                                .parentId(null) // Level 1 conformance - no nested contexts
                                .type(OperationType.STEP)
                                .action(OperationAction.RETRY)
                                .error(errorObject)
                                .stepOptions(StepOptions.builder()
                                        .nextAttemptDelaySeconds(Math.toIntExact(retryDecision.delay().toSeconds()))
                                        .build())
                                .build();
                        checkpointManager.checkpoint(retryUpdate).join();

                        // Suspend execution - DAR backend will re-invoke Lambda after delay
                        throw new SuspendExecutionException();
                    } else {
                        // Send FAIL action - retries exhausted
                        var failUpdate = OperationUpdate.builder()
                                .id(operationId)
                                .name(stepName)
                                .parentId(null) // Level 1 conformance - no nested contexts
                                .type(OperationType.STEP)
                                .action(OperationAction.FAIL)
                                .error(errorObject)
                                .build();
                        checkpointManager.checkpoint(failUpdate).join();

                        SneakyThrow.sneakyThrow(e);
                        return null; // unreachable
                    }
                } else {
                    // No retry strategy - send FAIL action immediately
                    var failUpdate = OperationUpdate.builder()
                            .id(operationId)
                            .name(stepName)
                            .parentId(null) // Level 1 conformance - no nested contexts
                            .type(OperationType.STEP)
                            .action(OperationAction.FAIL)
                            .error(ErrorObject.builder()
                                    .errorType(e.getClass().getSimpleName())
                                    .errorMessage(e.getMessage())
                                    .build())
                            .build();
                    checkpointManager.checkpoint(failUpdate).join();

                    SneakyThrow.sneakyThrow(e);
                    return null; // unreachable
                }
            }
        });

        return new DurableFuture<>(future);
    }
}
