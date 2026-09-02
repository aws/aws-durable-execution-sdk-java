// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeOptions;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.InvokeException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.offload.internal.ChainedInvokeOutputFrame;
import software.amazon.lambda.durable.offload.internal.ChainedInvokePayloadFrame;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Durable operation that invokes another Lambda function and waits for its result.
 *
 * @param <T> the result type from the invoked function
 * @param <I> the payload type sent to the invoked function
 */
public class InvokeOperation<T, I> extends SerializableDurableOperation<T> {
    private final String functionName;
    private final I payload;
    private final InvokeConfig invokeConfig;
    private final SerDes payloadSerDes;

    public InvokeOperation(
            OperationIdentifier operationIdentifier,
            String functionName,
            I payload,
            TypeToken<T> resultTypeToken,
            InvokeConfig config,
            DurableContextImpl durableContext) {
        super(operationIdentifier, resultTypeToken, config.serDes(), config.payloadOffloader(), durableContext);

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
                throw terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected invoke status: " + existing.statusAsString());
        }
    }

    private void startInvocation() {
        var serializedPayload = invokeConfig.usePayloadOffloaderForPayload()
                ? serializePayload(payload, payloadSerDes, SerDesPayloadKind.INVOKE_PAYLOAD, null)
                : payloadSerDes.serialize(payload);
        var update = OperationUpdate.builder()
                .action(OperationAction.START)
                .chainedInvokeOptions(ChainedInvokeOptions.builder()
                        .functionName(functionName)
                        .tenantId(invokeConfig.tenantId())
                        .build())
                .payload(
                        invokeConfig.usePayloadOffloaderForPayload()
                                ? ChainedInvokePayloadFrame.encode(serializedPayload)
                                : serializedPayload);

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
            case SUCCEEDED -> deserializeInvokeResult(result);
            case FAILED -> throw createInvokeFailure(op, InvokeFailedException::new);
            case TIMED_OUT -> throw createInvokeFailure(op, InvokeTimedOutException::new);
            case STOPPED -> throw createInvokeFailure(op, InvokeStoppedException::new);
            // Unexpected status which should not happen. This is added for forward-compatibility.
            default -> throw createInvokeFailure(op, InvokeException::new);
        };
    }

    private T deserializeInvokeResult(String result) {
        if (!invokeConfig.usePayloadOffloaderForPayload() || !ChainedInvokeOutputFrame.isFramed(result)) {
            return deserializeExternalResult(result);
        }
        var decoded = ChainedInvokeOutputFrame.decode(result);
        if (decoded.usesPayloadCodec()) {
            validatePayloadEnvelope(decoded.payload(), SerDesPayloadKind.RESULT, null);
        }
        return decoded.usesPayloadCodec()
                ? deserializeResult(decoded.payload())
                : deserializeExternalResult(decoded.payload());
    }

    private <E extends DurableOperationException> E createInvokeFailure(
            Operation operation, Function<Operation, E> exceptionFactory) {
        var details = operation.chainedInvokeDetails();
        var error = details != null ? details.error() : null;
        if (!invokeConfig.usePayloadOffloaderForPayload()
                || error == null
                || !ChainedInvokeOutputFrame.isFramed(error.errorData())) {
            return exceptionFactory.apply(operation);
        }

        var decoded = ChainedInvokeOutputFrame.decode(error.errorData());
        var errorData = decoded.payload();
        if (decoded.usesPayloadCodec()) {
            validatePayloadEnvelope(errorData, SerDesPayloadKind.EXCEPTION, null);
            errorData = resolveSerializedPayload(errorData, SerDesPayloadKind.EXCEPTION, null);
        }
        var decodedError = error.toBuilder().errorData(errorData).build();
        var decodedOperation = operation.toBuilder()
                .chainedInvokeDetails(details.toBuilder().error(decodedError).build())
                .build();
        return exceptionFactory.apply(decodedOperation);
    }
}
