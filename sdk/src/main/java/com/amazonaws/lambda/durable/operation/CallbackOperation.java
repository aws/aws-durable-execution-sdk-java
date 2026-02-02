// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.CallbackConfig;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.CallbackFailedException;
import com.amazonaws.lambda.durable.exception.CallbackTimeoutException;
import com.amazonaws.lambda.durable.exception.SerDesException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ExecutionPhase;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Phaser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.CallbackOptions;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

/** Durable operation for creating and waiting on external callbacks. */
public class CallbackOperation<T> implements DurableOperation<T> {

    private final String operationId;
    private final String name;
    private final TypeToken<T> resultTypeToken;
    private final CallbackConfig config;
    private final ExecutionManager executionManager;
    private final SerDes serDes;
    private final Phaser phaser;
    private static final Logger logger = LoggerFactory.getLogger(CallbackOperation.class);

    private String callbackId;

    public CallbackOperation(
            String operationId,
            String name,
            TypeToken<T> resultTypeToken,
            CallbackConfig config,
            ExecutionManager executionManager,
            SerDes serDes) {
        this.operationId = operationId;
        this.name = name;
        this.resultTypeToken = resultTypeToken;
        this.config = config;
        this.executionManager = executionManager;
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

    public String getCallbackId() {
        return callbackId;
    }

    @Override
    public void execute() {
        var existing = executionManager.getOperation(operationId);

        if (existing != null && existing.callbackDetails() != null) {
            // Replay: use existing callback ID
            callbackId = existing.callbackDetails().callbackId();

            switch (existing.status()) {
                case SUCCEEDED, FAILED, TIMED_OUT -> {
                    // Terminal state - complete phaser immediately
                    phaser.arriveAndDeregister();
                    return;
                }
                case STARTED -> {
                    // Still waiting - continue to polling
                }
                default -> throw new IllegalStateException("Unexpected callback status: " + existing.status());
            }
        } else {
            // First execution: checkpoint and get callback ID
            var update = OperationUpdate.builder()
                    .id(operationId)
                    .name(name)
                    .parentId(null)
                    .type(OperationType.CALLBACK)
                    .action(OperationAction.START)
                    .callbackOptions(buildCallbackOptions())
                    .build();

            executionManager.sendOperationUpdate(update).join();

            // Get the callback ID from the updated operation
            var op = executionManager.getOperation(operationId);
            callbackId = op.callbackDetails().callbackId();
        }

        // Start polling for callback completion (delay first poll to allow suspension)
        executionManager.pollForOperationUpdates(operationId, Instant.now().plusMillis(100), Duration.ofMillis(200));
    }

    @Override
    public T get() {
        // Get current context from ThreadLocal
        var currentContext = executionManager.getCurrentContext();

        if (phaser.getPhase() == ExecutionPhase.RUNNING.getValue()) {
            phaser.register();

            // Deregister current context - allows suspension
            executionManager.deregisterActiveThread(currentContext.contextId());

            // Block until callback completes
            phaser.arriveAndAwaitAdvance();

            // Reactivate current context
            executionManager.registerActiveThreadWithContext(currentContext.contextId(), currentContext.threadType());

            phaser.arriveAndDeregister();
        }

        // Get result based on status
        var op = executionManager.getOperation(operationId);
        if (op == null) {
            throw new IllegalStateException("Callback operation not found: " + operationId);
        }

        return switch (op.status()) {
            case SUCCEEDED -> {
                var result = op.callbackDetails().result();
                try {
                    yield serDes.deserialize(result, resultTypeToken);
                } catch (SerDesException e) {
                    logger.warn(
                            "Failed to deserialize callback result for callback ID '{}'. "
                                    + "Ensure the callback completion payload is base64-encoded.",
                            callbackId);
                    throw e;
                }
            }
            case FAILED ->
                throw new CallbackFailedException(op.callbackDetails().error());
            case TIMED_OUT -> throw new CallbackTimeoutException(callbackId);
            default -> throw new IllegalStateException("Unexpected callback status: " + op.status());
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
