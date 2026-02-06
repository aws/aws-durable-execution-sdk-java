// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import com.amazonaws.lambda.durable.exception.UnrecoverableDurableExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ExecutionPhase;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.util.ExceptionHelper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Phaser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

public abstract class BaseDurableOperation<T> implements DurableOperation<T> {
    private static final Logger logger = LoggerFactory.getLogger(BaseDurableOperation.class);

    private final String operationId;
    private final String name;
    private final OperationType operationType;
    private final ExecutionManager executionManager;
    private final TypeToken<T> resultTypeToken;
    private final SerDes resultSerDes;
    private final Phaser phaser;

    public BaseDurableOperation(
            String operationId,
            String name,
            OperationType operationType,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            ExecutionManager executionManager) {
        this.operationId = operationId;
        this.name = name;
        this.operationType = operationType;
        this.executionManager = executionManager;
        this.resultTypeToken = resultTypeToken;
        this.resultSerDes = resultSerDes;

        // todo: phaser could be used only in ExecutionManager and invisible from operations.
        this.phaser = executionManager.startPhaser(operationId);
    }

    /** Gets the unique identifier for this operation. */
    @Override
    public String getOperationId() {
        return operationId;
    }

    /** Gets the operation name (maybe null). */
    @Override
    public String getName() {
        return name;
    }

    @Override
    public OperationType getType() {
        return operationType;
    }

    protected Operation getOperation() {
        return executionManager.getOperationAndUpdateReplayState(getOperationId());
    }

    protected void validateCurrentThreadType() {
        ThreadType current = executionManager.getCurrentContext().threadType();
        if (current == ThreadType.STEP) {
            // throws an UnrecoverableDurableExecutionException to immediately terminate the execution
            var message = String.format(
                    "Nested %s operation is not supported on %s from within a %s execution.",
                    getType(), getName(), current);
            // terminate execution and throw the exception
            terminateExecution(new IllegalDurableOperationException(message));
        }
    }

    // phase control utilities
    protected Operation waitForOperationCompletionIfRunning() {
        // If we are in a replay where the operation is already complete (SUCCEEDED /
        // FAILED), the Phaser will be
        // advanced in .execute() already and we don't block but return the result
        // immediately.
        if (phaser.getPhase() == ExecutionPhase.RUNNING.getValue()) {
            // Operation not done yet
            phaser.register();

            var context = executionManager.getCurrentContext();
            // Deregister current context - allows suspension
            logger.debug(
                    "get() on {} attempting to deregister context: {}",
                    getType(),
                    executionManager.getCurrentContext().contextId());
            deregisterActiveThreadAndUnsetCurrentContext(context.contextId());

            // Block until operation completes
            logger.trace("Waiting for operation to finish {} (Phaser: {})", getOperationId(), phaser);
            phaser.arriveAndAwaitAdvance();

            // Reactivate current context
            registerActiveThread(context.contextId(), context.threadType());
            setCurrentContext(context.contextId(), context.threadType());

            // Complete phase 1
            phaser.arriveAndDeregister();
        }

        // Get result based on status
        var op = getOperation();
        if (op == null) {
            // throws an UnrecoverableDurableExecutionException to immediately terminate the execution
            throw new IllegalDurableOperationException(
                    String.format("{%s} operation not found: {%s}", getType(), getOperationId()));
        }
        return op;
    }

    protected void markCompletionDuringReplay() {
        // Operation is already completed (we are in a replay). We advance and
        // deregister from the Phaser
        // so that .get() doesn't block and returns the result immediately. See
        // StepOperation.get().
        logger.trace("Detected terminal status during replay. Advancing phaser 0 -> 1 {}.", phaser);
        phaser.arriveAndDeregister(); // Phase 0 -> 1
    }

    protected T terminateExecution(UnrecoverableDurableExecutionException exception) {
        executionManager.terminateExecution(exception.getErrorObject());
        throw exception;
    }

    // advanced phase control used by Step only
    protected void deregisterActiveThreadAndUnsetCurrentContext(String threadId) {
        executionManager.deregisterActiveThreadAndUnsetCurrentContext(threadId);
    }

    protected void registerActiveThread(String threadId, ThreadType threadType) {
        executionManager.registerActiveThread(threadId, threadType);
    }

    protected void setCurrentContext(String stepThreadId, ThreadType step) {
        executionManager.setCurrentContext(stepThreadId, step);
    }

    // polling and checkpointing
    protected void pollForOperationUpdates(String operationId, Instant firstPoll, Duration duration) {
        executionManager.pollForOperationUpdates(operationId, firstPoll, duration);
    }

    protected void pollUntilReady(
            String operationId, CompletableFuture<Void> pendingFuture, Instant firstPoll, Duration duration) {
        executionManager.pollUntilReady(operationId, pendingFuture, firstPoll, duration);
    }

    protected void sendOperationUpdate(OperationUpdate update) {
        executionManager.sendOperationUpdate(update).join();
    }

    protected void sendOperationUpdateAsync(OperationUpdate update) {
        executionManager.sendOperationUpdate(update);
    }

    // serialization/deserialization utilities
    protected T deserializeResult(String result) {
        return resultSerDes.deserialize(result, resultTypeToken);
    }

    protected String serializeResult(T result) {
        return resultSerDes.serialize(result);
    }

    protected ErrorObject serializeException(Throwable throwable) {
        return ExceptionHelper.buildErrorObject(throwable, resultSerDes);
    }

    protected Throwable deserializeException(ErrorObject errorObject) throws ClassNotFoundException {
        var errorType = errorObject.errorType();
        var errorData = errorObject.errorData();
        Throwable original = null;
        Class<?> exceptionClass = Class.forName(errorType);
        if (Throwable.class.isAssignableFrom(exceptionClass)) {
            original = resultSerDes.deserialize(errorData, TypeToken.get(exceptionClass.asSubclass(Throwable.class)));

            if (original != null) {
                original.setStackTrace(ExceptionHelper.deserializeStackTrace(errorObject.stackTrace()));
            }
        }
        return original;
    }
}
