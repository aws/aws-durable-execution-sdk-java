// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.InvokeConfig;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.InvokeException;
import com.amazonaws.lambda.durable.exception.InvokeFailedException;
import com.amazonaws.lambda.durable.exception.InvokeStoppedException;
import com.amazonaws.lambda.durable.exception.InvokeTimedOutException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ExecutionPhase;
import com.amazonaws.lambda.durable.execution.ThreadType;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Phaser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeOptions;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

public class InvokeOperation<T, U> implements DurableOperation<T> {
    private static final Logger logger = LoggerFactory.getLogger(InvokeOperation.class);

    private final String operationId;
    private final TypeToken<T> resultTypeToken;
    private final String name;
    private final String functionName;
    private final U payload;
    private final InvokeConfig invokeConfig;
    private final ExecutionManager executionManager;
    private final Phaser phaser;
    private final SerDes serDes;
    private final SerDes payloadSerDes;

    public InvokeOperation(
            String operationId,
            String name,
            String functionName,
            U payload,
            TypeToken<T> resultTypeToken,
            InvokeConfig config,
            ExecutionManager executionManager,
            SerDes defaultSerDes) {
        this.operationId = operationId;
        this.name = name;
        this.functionName = functionName;
        this.payload = payload;
        this.resultTypeToken = resultTypeToken;
        this.invokeConfig = config;
        this.executionManager = executionManager;
        this.serDes = (config != null && config.serDes() != null) ? config.serDes() : defaultSerDes;
        this.payloadSerDes =
                (config != null && config.payloadSerDes() != null) ? config.payloadSerDes() : defaultSerDes;

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

    /** Starts the operation. Returns immediately after starting background work or checkpointing. Does not block. */
    @Override
    public void execute() {
        var existing = executionManager.getOperationAndUpdateReplayState(operationId);
        if (existing == null) {
            // first execution
            startInvocation();
            waitTimeout();
        } else {
            // replay
            switch (existing.status()) {
                case STARTED -> {
                    // The result isn't ready. Need to wait more
                    waitTimeout();
                }
                case SUCCEEDED, FAILED, TIMED_OUT, STOPPED -> {
                    // Operation is already completed (we are in a replay). We advance and
                    // deregister from the Phaser
                    // so that .get() doesn't block and returns the result immediately. See
                    // InvokeOperation.get().
                    logger.trace("Detected terminal status during replay. Advancing phaser 0 -> 1 {}.", phaser);
                    phaser.arriveAndDeregister(); // Phase 0 -> 1
                }
                default -> {
                    throw new IllegalStateException("Unexpected invoke status: " + existing.statusAsString());
                }
            }
        }
    }

    private void waitTimeout() {
        var waitTime = invokeConfig.timeout() != null ? invokeConfig.timeout() : Duration.ofSeconds(1);
        logger.debug("Remaining invoke wait time: {} seconds", waitTime.toSeconds());
        Instant firstPoll = Instant.now().plus(waitTime).plusMillis(25);
        executionManager.pollForOperationUpdates(operationId, firstPoll, Duration.ofMillis(200));
    }

    private void startInvocation() {
        var update = OperationUpdate.builder()
                .id(operationId)
                .name(name)
                .parentId(null)
                .type(OperationType.CHAINED_INVOKE)
                .action(OperationAction.START)
                .chainedInvokeOptions(ChainedInvokeOptions.builder()
                        .functionName(functionName)
                        .tenantId(invokeConfig.tenantId())
                        .build())
                .payload(payloadSerDes.serialize(this.payload))
                .build();

        executionManager.sendOperationUpdate(update).join();
    }

    /**
     * Blocks until the operation completes and returns the result.
     *
     * <p>Handles:
     *
     * <ul>
     *   <li>Thread deregistration (allows suspension)
     *   <li>Phaser blocking (waits for operation to complete)
     *   <li>Thread reactivation (resumes execution)
     *   <li>Result retrieval
     * </ul>
     *
     * @return the operation result
     */
    @Override
    public T get() {
        var currentContext = executionManager.getCurrentContext();

        // Nested steps are not supported
        if (currentContext.threadType() == ThreadType.STEP) {
            throw new IllegalStateException("Nested invoke calling is not supported. Cannot call invoke on '" + name
                    + "' from within a step's execution.");
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

        var op = executionManager.getOperation(operationId);
        if (op == null) {
            throw new IllegalStateException("Invoke '" + name + "' operation not found");
        }

        var invokeDetails = op.chainedInvokeDetails();
        var result = invokeDetails != null ? invokeDetails.result() : null;
        var error = invokeDetails != null ? invokeDetails.error() : null;
        return switch (op.status()) {
            case SUCCEEDED -> serDes.deserialize(result, resultTypeToken);
            case FAILED -> throw new InvokeFailedException(error);
            case TIMED_OUT -> throw new InvokeTimedOutException(error);
            case STOPPED -> throw new InvokeStoppedException(error);
            // Unexpected status which should not happen. This is added for forward-compatibility.
            default -> throw new InvokeException(op.status(), error);
        };
    }
}
