// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import software.amazon.awssdk.services.lambda.model.ChainedInvokeOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.InvokeConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.InvokeException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Durable operation that invokes another Lambda function and waits for its result.
 *
 * @param <T> the result type from the invoked function
 * @param <U> the payload type sent to the invoked function
 */
public class InvokeOperation<T, U> extends BaseDurableOperation<T> {
    private final String functionName;
    private final U payload;
    private final InvokeConfig invokeConfig;
    private final SerDes payloadSerDes;

    public InvokeOperation(
            OperationIdentifier operationIdentifier,
            String functionName,
            U payload,
            TypeToken<T> resultTypeToken,
            InvokeConfig config,
            DurableContextImpl durableContext) {
        super(operationIdentifier, resultTypeToken, config.serDes(), durableContext);

        this.functionName = functionName;
        this.payload = payload;
        this.invokeConfig = config;
        this.payloadSerDes = config.payloadSerDes() != null ? config.payloadSerDes() : config.serDes();
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        startInvocation();
        pollForOperationUpdates();
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            // The result isn't ready. Need to wait more
            case STARTED -> pollForOperationUpdates();
            case SUCCEEDED, FAILED, TIMED_OUT, STOPPED -> markAlreadyCompleted();
            default ->
                terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected invoke status: " + existing.statusAsString());
        }
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
