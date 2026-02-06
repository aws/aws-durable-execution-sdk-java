// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.CallbackConfig;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.CallbackFailedException;
import com.amazonaws.lambda.durable.exception.CallbackTimeoutException;
import com.amazonaws.lambda.durable.exception.IllegalDurableOperationException;
import com.amazonaws.lambda.durable.exception.SerDesException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.CallbackOptions;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/** Durable operation for creating and waiting on external callbacks. */
public class CallbackOperation<T> extends BaseDurableOperation<T> implements DurableOperation<T> {

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

    public String getCallbackId() {
        return callbackId;
    }

    @Override
    public void execute() {
        var existing = getOperation();

        if (existing != null && existing.callbackDetails() != null) {
            // Replay: use existing callback ID
            callbackId = existing.callbackDetails().callbackId();

            switch (existing.status()) {
                case SUCCEEDED, FAILED, TIMED_OUT -> {
                    // Terminal state - complete phaser immediately
                    markCompletionDuringReplay();
                    return;
                }
                case STARTED -> {
                    // Still waiting - continue to polling
                }
                default -> {
                    // throws an UnrecoverableDurableExecutionException to immediately terminate the execution
                    throw new IllegalDurableOperationException("Unexpected callback status: " + existing.status());
                }
            }
        } else {
            // First execution: checkpoint and get callback ID
            var update = OperationUpdate.builder()
                    .id(getOperationId())
                    .name(getName())
                    .parentId(null)
                    .type(OperationType.CALLBACK)
                    .action(OperationAction.START)
                    .callbackOptions(buildCallbackOptions())
                    .build();

            sendOperationUpdate(update);

            // Get the callback ID from the updated operation
            var op = getOperation();
            callbackId = op.callbackDetails().callbackId();
        }

        // Start polling for callback completion (delay first poll to allow suspension)
        pollForOperationUpdates(getOperationId(), Instant.now().plusMillis(100), Duration.ofMillis(200));
    }

    @Override
    public T get() {
        var op = waitForOperationCompletionIfRunning();

        return switch (op.status()) {
            case SUCCEEDED -> {
                var result = op.callbackDetails().result();
                try {
                    yield deserializeResult(result);
                } catch (SerDesException e) {
                    logger.warn(
                            "Failed to deserialize callback result for callback ID '{}'. "
                                    + "Ensure the callback completion payload is base64-encoded.",
                            callbackId);
                    throw e;
                }
            }
            case FAILED -> throw new CallbackFailedException(op);
            case TIMED_OUT -> throw new CallbackTimeoutException(callbackId, op);
            default ->
                terminateExecution(new IllegalDurableOperationException("Unexpected callback status: " + op.status()));
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
