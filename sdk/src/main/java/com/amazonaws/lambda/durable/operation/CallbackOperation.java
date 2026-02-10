// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.CallbackConfig;
import com.amazonaws.lambda.durable.DurableCallbackFuture;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.CallbackFailedException;
import com.amazonaws.lambda.durable.exception.CallbackTimeoutException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.CallbackOptions;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/** Durable operation for creating and waiting on external callbacks. */
public class CallbackOperation<T> extends BaseDurableOperation<T> implements DurableCallbackFuture<T> {

    private static final Logger logger = LoggerFactory.getLogger(CallbackOperation.class);

    private final CallbackConfig config;

    private String callbackId;

    public CallbackOperation(
            String operationId,
            String name,
            TypeToken<T> resultTypeToken,
            CallbackConfig config,
            ExecutionManager executionManager) {
        super(operationId, name, OperationType.CALLBACK, resultTypeToken, config.serDes(), executionManager);
        this.config = config;
    }

    public String callbackId() {
        return callbackId;
    }

    @Override
    public void execute() {
        var existing = getOperation();

        if (existing != null) {
            validateReplay(existing);
        }

        if (existing != null && existing.callbackDetails() != null) {
            // Replay: use existing callback ID
            callbackId = existing.callbackDetails().callbackId();

            switch (existing.status()) {
                case SUCCEEDED, FAILED, TIMED_OUT -> {
                    // Terminal state - complete phaser immediately
                    markAlreadyCompleted();
                    return;
                }
                case STARTED -> {
                    // Still waiting - continue to polling
                }
                default ->
                    terminateExecutionWithIllegalDurableOperationException(
                            "Unexpected callback status: " + existing.status());
            }
        } else {
            // First execution: checkpoint and get callback ID
            var update =
                    OperationUpdate.builder().action(OperationAction.START).callbackOptions(buildCallbackOptions());

            sendOperationUpdate(update);

            // Get the callback ID from the updated operation
            var op = getOperation();
            callbackId = op.callbackDetails().callbackId();
        }

        // Start polling for callback completion (delay first poll to allow suspension)
        pollForOperationUpdates();
    }

    @Override
    public T get() {
        var op = waitForOperationCompletion();

        return switch (op.status()) {
            case SUCCEEDED -> deserializeResult(op.callbackDetails().result());
            case FAILED -> throw new CallbackFailedException(op);
            case TIMED_OUT -> throw new CallbackTimeoutException(op);
            default ->
                terminateExecutionWithIllegalDurableOperationException("Unexpected callback status: " + op.status());
        };
    }

    private CallbackOptions buildCallbackOptions() {
        var builder = CallbackOptions.builder();
        if (config != null) {
            if (config.timeout() != null) {
                builder.timeoutSeconds((int) config.timeout().toSeconds());
            }
            if (config.heartbeatTimeout() != null) {
                builder.heartbeatTimeoutSeconds((int) config.heartbeatTimeout().toSeconds());
            }
        }
        return builder.build();
    }
}
