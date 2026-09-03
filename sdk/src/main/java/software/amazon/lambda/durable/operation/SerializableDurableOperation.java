// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.execution.PayloadCodec;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.PayloadOffloaders;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Base class for all durable operations (STEP, WAIT, etc.).
 *
 * <p>Key methods:
 *
 * <ul>
 *   <li>{@code execute()} starts the operation (returns immediately)
 *   <li>{@code get()} blocks until complete and returns the result
 * </ul>
 *
 * <p>The separation allows:
 *
 * <ul>
 *   <li>Starting multiple async operations quickly
 *   <li>Blocking on results later when needed
 *   <li>Proper thread coordination via future
 * </ul>
 */
public abstract class SerializableDurableOperation<T> extends BaseDurableOperation implements DurableFuture<T> {
    private static final Logger logger = LoggerFactory.getLogger(SerializableDurableOperation.class);

    protected record SerializedResult<T>(String serialized, T deserialized) {}

    private final TypeToken<T> resultTypeToken;
    private final SerDes resultSerDes;
    private final PayloadOffloader payloadOffloader;

    /**
     * Constructs a new durable operation.
     *
     * @param operationIdentifier the unique identifier for this operation
     * @param resultTypeToken the type token for deserializing the result
     * @param resultSerDes the serializer/deserializer for the result
     * @param durableContext the parent context this operation belongs to
     */
    protected SerializableDurableOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext) {
        this(operationIdentifier, resultTypeToken, resultSerDes, null, durableContext, null, false);
    }

    protected SerializableDurableOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            PayloadOffloader payloadOffloader,
            DurableContextImpl durableContext) {
        this(operationIdentifier, resultTypeToken, resultSerDes, payloadOffloader, durableContext, null, false);
    }

    /**
     * Constructs a new durable operation.
     *
     * @param operationIdentifier the unique identifier for this operation
     * @param resultTypeToken the type token for deserializing the result
     * @param resultSerDes the serializer/deserializer for the result
     * @param durableContext the parent context this operation belongs to
     * @param isVirtual whether this is a virtual operation that should not be persisted
     * @param parentOperation the parent operation if this is a branch/iteration of a ConcurrencyOperation
     */
    protected SerializableDurableOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            DurableContextImpl durableContext,
            BaseDurableOperation parentOperation,
            boolean isVirtual) {
        this(operationIdentifier, resultTypeToken, resultSerDes, null, durableContext, parentOperation, isVirtual);
    }

    protected SerializableDurableOperation(
            OperationIdentifier operationIdentifier,
            TypeToken<T> resultTypeToken,
            SerDes resultSerDes,
            PayloadOffloader payloadOffloader,
            DurableContextImpl durableContext,
            BaseDurableOperation parentOperation,
            boolean isVirtual) {
        super(operationIdentifier, durableContext, parentOperation, isVirtual);
        this.resultTypeToken = resultTypeToken;
        this.resultSerDes = resultSerDes;
        this.payloadOffloader = payloadOffloader;
    }

    /**
     * Deserializes a result string into the operation's result type.
     *
     * @param result the serialized result string
     * @return the deserialized result
     * @throws SerDesException if deserialization fails
     */
    protected T deserializeResult(String result) {
        return deserializeResult(result, SerDesPayloadKind.RESULT, null);
    }

    /** Deserializes externally supplied data without interpreting SDK payload envelopes. */
    protected T deserializeExternalResult(String result) {
        return resultSerDes.deserialize(result, resultTypeToken);
    }

    /** Deserializes a result with explicit payload kind and attempt metadata. */
    protected T deserializeResult(String result, SerDesPayloadKind payloadKind, Integer attempt) {
        try {
            if (!usesPayloadCodec(result)) {
                return resultSerDes.deserialize(result, resultTypeToken);
            }
            return executionManager
                    .getPayloadCodec()
                    .deserialize(
                            result,
                            resultTypeToken,
                            resultSerDes,
                            payloadOffloader,
                            payloadContext(payloadKind, attempt));
        } catch (SerDesException e) {
            logger.warn(
                    "Failed to deserialize {} result for operation name '{}'. Ensure the result is properly encoded.",
                    getType(),
                    getName());
            throw e;
        }
    }

    /**
     * Serializes the result and returns the value that should be exposed to callers.
     *
     * <p>Use this for operations that cache a first-execution result instead of reading it back from checkpoint data.
     * This keeps first execution consistent with replay when a SerDes normalizes or otherwise changes the value.
     *
     * @param result the result to serialize
     * @return the serialized string and the deserialized result
     */
    protected SerializedResult<T> serializeAndDeserializeResult(T result) {
        return serializeAndDeserializeResult(result, SerDesPayloadKind.RESULT, null);
    }

    /** Serializes a result with explicit payload kind and attempt metadata. */
    protected SerializedResult<T> serializeAndDeserializeResult(
            T result, SerDesPayloadKind payloadKind, Integer attempt) {
        var serialized = serializePayload(result, resultSerDes, payloadKind, attempt);
        var deserialized =
                shouldDeserializeAfterSerialization() ? deserializeResult(serialized, payloadKind, attempt) : result;
        return new SerializedResult<>(serialized, deserialized);
    }

    /**
     * Normalizes a result through SerDes without offloading it.
     *
     * <p>Use this when an operation needs the same first-execution value normalization as replay but will not persist
     * an update.
     */
    protected SerializedResult<T> normalizeResult(T result) {
        var serialized = resultSerDes.serialize(result);
        var deserialized =
                shouldDeserializeAfterSerialization() ? resultSerDes.deserialize(serialized, resultTypeToken) : result;
        return new SerializedResult<>(serialized, deserialized);
    }

    /** Serializes an operation-owned payload with the operation's offloader policy. */
    protected String serializePayload(Object value, SerDes serDes, SerDesPayloadKind payloadKind, Integer attempt) {
        if (!hasActivePayloadOffloader()) {
            var serialized = serDes.serialize(value);
            return PayloadCodec.isOffloadEnvelope(serialized)
                    ? executionManager
                            .getPayloadCodec()
                            .serializePreEncodedPayload(
                                    serialized, payloadOffloader, payloadContext(payloadKind, attempt))
                    : serialized;
        }
        return executionManager
                .getPayloadCodec()
                .serialize(value, serDes, payloadOffloader, payloadContext(payloadKind, attempt));
    }

    /**
     * Serializes a throwable into an {@link ErrorObject} for checkpointing.
     *
     * @param throwable the exception to serialize
     * @return the serialized error object
     */
    @SuppressWarnings("ThrowableNotThrown")
    protected ErrorObject serializeException(Throwable throwable) {
        return serializeException(throwable, null);
    }

    /** Serializes a throwable with attempt metadata. */
    protected ErrorObject serializeException(Throwable throwable, Integer attempt) {
        return serializeException(throwable, attempt, true);
    }

    /** Serializes an exception for local result normalization without offloading it. */
    protected ErrorObject serializeExceptionWithoutOffloading(Throwable throwable) {
        return serializeException(throwable, null, false);
    }

    /** Rebinds a forwarded operation error to this operation's payload policy before checkpointing it. */
    protected ErrorObject rebindForwardedError(DurableOperationException exception) {
        return rebindForwardedError(exception, null);
    }

    /** Rebinds a forwarded operation error with attempt metadata before checkpointing it. */
    protected ErrorObject rebindForwardedError(DurableOperationException exception, Integer attempt) {
        var error = exception.getErrorObject();
        if (error == null || error.errorData() == null) {
            return error;
        }
        var targetContext = payloadContext(SerDesPayloadKind.EXCEPTION, attempt);
        var errorData = exception.getPayloadOffloadContext() == null
                ? executionManager
                        .getPayloadCodec()
                        .serializePreEncodedPayload(error.errorData(), payloadOffloader, targetContext)
                : executionManager
                        .getPayloadCodec()
                        .rebindSerializedPayload(
                                error.errorData(),
                                exception.getPayloadOffloader(),
                                exception.getPayloadOffloadContext(),
                                payloadOffloader,
                                targetContext);
        return error.toBuilder().errorData(errorData).build();
    }

    /** Resolves forwarded error data for local use without creating a target-owned external payload. */
    protected ErrorObject resolveForwardedErrorWithoutOffloading(DurableOperationException exception) {
        var error = exception.getErrorObject();
        if (error == null || error.errorData() == null) {
            return error;
        }
        var serialized = exception.getPayloadOffloadContext() == null
                ? error.errorData()
                : executionManager
                        .getPayloadCodec()
                        .resolveSerializedPayload(
                                error.errorData(),
                                exception.getPayloadOffloader(),
                                exception.getPayloadOffloadContext());
        var errorData = executionManager
                .getPayloadCodec()
                .serializePreEncodedPayload(
                        serialized, PayloadOffloaders.disabled(), payloadContext(SerDesPayloadKind.EXCEPTION, null));
        return error.toBuilder().errorData(errorData).build();
    }

    protected <E extends DurableOperationException> E attachPayloadSource(
            E exception, SerDesPayloadKind kind, Integer attempt) {
        return attachPayloadSource(exception, kind, attempt, payloadOffloader);
    }

    protected <E extends DurableOperationException> E attachPayloadSource(
            E exception, SerDesPayloadKind kind, Integer attempt, PayloadOffloader sourceOffloader) {
        var error = exception.getErrorObject();
        if (error == null || !PayloadCodec.isOffloadEnvelope(error.errorData())) {
            return exception;
        }
        exception.withPayloadSource(sourceOffloader, payloadContext(kind, attempt));
        return exception;
    }

    /** Validates an SDK payload envelope against this operation's payload identity. */
    protected void validatePayloadEnvelope(String checkpointPayload, SerDesPayloadKind kind, Integer attempt) {
        executionManager.getPayloadCodec().validateEnvelope(checkpointPayload, payloadContext(kind, attempt));
    }

    /** Resolves operation-owned payload data through the configured offloader and verifies its envelope. */
    protected String resolveSerializedPayload(String checkpointPayload, SerDesPayloadKind kind, Integer attempt) {
        return executionManager
                .getPayloadCodec()
                .resolveSerializedPayload(checkpointPayload, payloadOffloader, payloadContext(kind, attempt));
    }

    private ErrorObject serializeException(Throwable throwable, Integer attempt, boolean allowOffloading) {
        final String errorData;
        if (allowOffloading) {
            errorData = serializePayload(throwable, resultSerDes, SerDesPayloadKind.EXCEPTION, attempt);
        } else {
            var serialized = resultSerDes.serialize(throwable);
            errorData = PayloadCodec.isOffloadEnvelope(serialized)
                    ? executionManager
                            .getPayloadCodec()
                            .serializePreEncodedPayload(
                                    serialized,
                                    PayloadOffloaders.disabled(),
                                    payloadContext(SerDesPayloadKind.EXCEPTION, attempt))
                    : serialized;
        }
        var error = ErrorObject.builder()
                .errorType(throwable.getClass().getName())
                .errorMessage(throwable.getMessage())
                .errorData(errorData)
                .stackTrace(ExceptionHelper.serializeStackTrace(throwable.getStackTrace()))
                .build();
        if (shouldDeserializeAfterSerialization()) {
            if (allowOffloading) {
                deserializeException(error, attempt);
            } else {
                deserializeException(error, attempt, PayloadOffloaders.disabled());
            }
        }
        return error;
    }

    private boolean shouldDeserializeAfterSerialization() {
        var config = getContext().getDurableConfig();
        return config == null || config.shouldDeserializeAfterSerialization();
    }

    /**
     * Deserializes an {@link ErrorObject} back into a throwable, reconstructing the original exception type and stack
     * trace when possible. Falls back to null if the exception class is not found or deserialization fails.
     *
     * @param errorObject the serialized error object
     * @return the reconstructed throwable, or null if reconstruction is not possible
     */
    protected Throwable deserializeException(ErrorObject errorObject) {
        return deserializeException(errorObject, null);
    }

    /** Deserializes a throwable with attempt metadata. */
    protected Throwable deserializeException(ErrorObject errorObject, Integer attempt) {
        return deserializeException(errorObject, attempt, payloadOffloader);
    }

    protected Throwable deserializeException(
            ErrorObject errorObject, Integer attempt, PayloadOffloader sourceOffloader) {
        Throwable original = null;
        if (errorObject == null) {
            return original;
        }
        var errorType = errorObject.errorType();
        var errorData = errorObject.errorData();

        if (errorType == null) {
            return original;
        }
        try {

            Class<?> exceptionClass = Class.forName(errorType);
            if (Throwable.class.isAssignableFrom(exceptionClass)) {
                var exceptionType = TypeToken.get(exceptionClass.asSubclass(Throwable.class));
                if (usesPayloadCodec(errorData, sourceOffloader)) {
                    original = executionManager
                            .getPayloadCodec()
                            .deserialize(
                                    errorData,
                                    exceptionType,
                                    resultSerDes,
                                    sourceOffloader,
                                    payloadContext(SerDesPayloadKind.EXCEPTION, attempt));
                } else {
                    original = resultSerDes.deserialize(errorData, exceptionType);
                }

                if (original != null) {
                    original.setStackTrace(ExceptionHelper.deserializeStackTrace(errorObject.stackTrace()));
                }
            }
        } catch (ClassNotFoundException e) {
            logger.warn("Cannot re-construct original exception type. Falling back to generic StepFailedException.");
        } catch (SerDesException e) {
            logger.warn("Cannot deserialize original exception data. Falling back to generic StepFailedException.", e);
        }
        return original;
    }

    private boolean usesPayloadCodec(String checkpointPayload) {
        return usesPayloadCodec(checkpointPayload, payloadOffloader);
    }

    private boolean usesPayloadCodec(String checkpointPayload, PayloadOffloader sourceOffloader) {
        return sourceOffloader != null && !PayloadOffloaders.isDisabled(sourceOffloader)
                || PayloadCodec.isOffloadEnvelope(checkpointPayload);
    }

    private boolean hasActivePayloadOffloader() {
        return payloadOffloader != null && !PayloadOffloaders.isDisabled(payloadOffloader);
    }

    private PayloadOffloadContext payloadContext(SerDesPayloadKind kind, Integer attempt) {
        return PayloadOffloadContext.forOperation(
                executionManager.getDurableExecutionArn(),
                getOperationIdentifier(),
                getContext().getParentId(),
                kind,
                attempt);
    }

    public abstract T get();
}
