// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.CallbackOptions;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.CallbackConfig;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.execution.ExecutionManager;

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
            ExecutionManager executionManager,
            String parentId) {
        super(operationId, name, OperationType.CALLBACK, resultTypeToken, config.serDes(), executionManager, parentId);
        this.config = config;
    }

    /** Convenience constructor for root-context operations where parentId is null. */
    public CallbackOperation(
            String operationId,
            String name,
            TypeToken<T> resultTypeToken,
            CallbackConfig config,
            ExecutionManager executionManager) {
        this(operationId, name, resultTypeToken, config, executionManager, null);
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
                    // Terminal state - complete the operation immediately
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
