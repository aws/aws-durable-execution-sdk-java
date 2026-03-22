// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import software.amazon.awssdk.services.lambda.model.CallbackOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.model.OperationIdentifier;

/** Durable operation for creating and waiting on external callbacks. */
public class CallbackOperation<T> extends BaseDurableOperation<T> implements DurableCallbackFuture<T> {

    private final CallbackConfig config;

    private String callbackId;

    public CallbackOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            CallbackConfig config,
            DurableContextImpl durableContext) {
        super(operationIdentifier, resultTypeToken, config.serDes(), durableContext);
        this.config = config;
    }

    public String callbackId() {
        return callbackId;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        // First execution: checkpoint and get callback ID
        var update = OperationUpdate.builder().action(OperationAction.START).callbackOptions(buildCallbackOptions());

        sendOperationUpdate(update);

        // Get the callback ID from the updated operation
        var op = getOperation();
        callbackId = op.callbackDetails().callbackId();

        pollForOperationUpdates();
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
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
