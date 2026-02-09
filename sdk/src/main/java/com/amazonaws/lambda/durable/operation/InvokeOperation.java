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
import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeOptions;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

public class InvokeOperation<T, U> extends BaseDurableOperation<T> {
    private static final Logger logger = LoggerFactory.getLogger(InvokeOperation.class);

    private final String functionName;
    private final U payload;
    private final InvokeConfig invokeConfig;
    private final SerDes payloadSerDes;

    public InvokeOperation(
            String operationId,
            String name,
            String functionName,
            U payload,
            TypeToken<T> resultTypeToken,
            InvokeConfig config,
            ExecutionManager executionManager) {
        super(operationId, name, OperationType.CHAINED_INVOKE, resultTypeToken, config.serDes(), executionManager);

        this.functionName = functionName;
        this.payload = payload;
        this.invokeConfig = config;
        this.payloadSerDes = config.payloadSerDes() != null ? config.payloadSerDes() : config.serDes();
    }

    /** Starts the operation. Returns immediately after starting background work or checkpointing. Does not block. */
    @Override
    public void execute() {
        var existing = getOperation();
        if (existing == null) {
            // first execution
            startInvocation();
            waitTimeout();
        } else {
            validateReplay(existing);
            // replay
            switch (existing.status()) {
                // The result isn't ready. Need to wait more
                case STARTED -> waitTimeout();
                case SUCCEEDED, FAILED, TIMED_OUT, STOPPED -> markAlreadyCompleted();
                default ->
                    terminateExecutionWithIllegalDurableOperationException(
                            "Unexpected invoke status: " + existing.statusAsString());
            }
        }
    }

    private void waitTimeout() {
        var waitTime = invokeConfig.timeout() != null ? invokeConfig.timeout() : Duration.ofSeconds(1);
        logger.debug("Remaining invoke wait time: {} seconds", waitTime.toSeconds());
        Instant firstPoll = Instant.now().plus(waitTime).plusMillis(25);
        pollForOperationUpdates(firstPoll, Duration.ofMillis(200));
    }

    private void startInvocation() {
        var update = OperationUpdate.builder()
                .action(OperationAction.START)
                .chainedInvokeOptions(ChainedInvokeOptions.builder()
                        .functionName(functionName)
                        .tenantId(invokeConfig.tenantId())
                        .build())
                .payload(payloadSerDes.serialize(this.payload));

        sendOperationUpdate(update);
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
        var op = waitForOperationCompletion();
        var invokeDetails = op.chainedInvokeDetails();
        var result = invokeDetails != null ? invokeDetails.result() : null;
        return switch (op.status()) {
            case SUCCEEDED -> deserializeResult(result);
            case FAILED -> throw new InvokeFailedException(op);
            case TIMED_OUT -> throw new InvokeTimedOutException(op);
            case STOPPED -> throw new InvokeStoppedException(op);
            // Unexpected status which should not happen. This is added for forward-compatibility.
            default -> throw new InvokeException(op);
        };
    }
}
