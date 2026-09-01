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
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.serde.ComposableSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
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
        this(operationIdentifier, resultTypeToken, resultSerDes, durableContext, null, false);
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
        super(operationIdentifier, durableContext, parentOperation, isVirtual);
        this.resultTypeToken = resultTypeToken;
        this.resultSerDes = resultSerDes;
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

    /** Deserializes a result with explicit payload kind and attempt metadata. */
    protected T deserializeResult(String result, SerDesPayloadKind payloadKind, Integer attempt) {
        try {
            return getSerDesRunner()
                    .deserialize(resultSerDes, result, resultTypeToken, createSerDesContext(payloadKind, attempt));
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
        var context = createSerDesContext(payloadKind, attempt);
        var serialized = getSerDesRunner().serialize(resultSerDes, result, context);
        var deserialized = shouldDeserializeAfterSerialization()
                ? getSerDesRunner().deserialize(resultSerDes, serialized, resultTypeToken, context)
                : result;
        return new SerializedResult<>(serialized, deserialized);
    }

    /**
     * Normalizes a result that will not be checkpointed without invoking composable persistence stages.
     *
     * <p>Virtual operations and children that finish after their parent completes still preserve value-codec round-trip
     * behavior, but must not publish external payloads whose envelopes will be discarded.
     */
    protected T normalizeUnpersistedResult(T result) {
        if (!shouldDeserializeAfterSerialization()) {
            return result;
        }
        var normalizationSerDes =
                resultSerDes instanceof ComposableSerDes composable ? composable.getValueCodec() : resultSerDes;
        var context = createSerDesContext(SerDesPayloadKind.RESULT, null);
        var serialized = getSerDesRunner().serialize(normalizationSerDes, result, context);
        return getSerDesRunner().deserialize(normalizationSerDes, serialized, resultTypeToken, context);
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
        var context = createSerDesContext(SerDesPayloadKind.EXCEPTION, attempt);
        var error = ErrorObject.builder()
                .errorType(throwable.getClass().getName())
                .errorMessage(throwable.getMessage())
                .errorData(getSerDesRunner().serialize(resultSerDes, throwable, context))
                .stackTrace(ExceptionHelper.serializeStackTrace(throwable.getStackTrace()))
                .build();
        if (shouldDeserializeAfterSerialization()) {
            deserializeException(error, attempt);
        }
        return error;
    }

    /**
     * Re-serializes an exception forwarded from another durable operation under this operation's context.
     *
     * <p>Context-dependent SerDes implementations may store the source error data under the producing operation or
     * invoked execution. Rebinding reconstructable exceptions prevents a parent checkpoint from later trying to read
     * that data using the parent's unrelated entity identity.
     */
    protected ErrorObject rebindForwardedException(DurableOperationException exception) {
        return rebindForwardedException(exception, null);
    }

    /**
     * Re-serializes an exception forwarded from another durable operation under this operation's attempt context.
     *
     * @param exception the forwarded durable operation exception
     * @param attempt the receiving operation's attempt, or {@code null} when attempts do not apply
     * @return error data owned by this operation when the original exception can be reconstructed; otherwise the
     *     forwarded error data
     */
    protected ErrorObject rebindForwardedException(DurableOperationException exception, Integer attempt) {
        var error = exception.getErrorObject();
        if (error == null || exception.getOperation() == null) {
            return error;
        }
        var original = exception.deserializedError();
        return original != null ? serializeException(original, attempt) : error;
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
        return deserializeExceptionWithContext(errorObject, createSerDesContext(SerDesPayloadKind.EXCEPTION, attempt));
    }

    private Throwable deserializeExceptionWithContext(ErrorObject errorObject, SerDesContext context) {
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
                original = getSerDesRunner()
                        .deserialize(
                                resultSerDes,
                                errorData,
                                TypeToken.get(exceptionClass.asSubclass(Throwable.class)),
                                context);

                if (original != null) {
                    original.setStackTrace(ExceptionHelper.deserializeStackTrace(errorObject.stackTrace()));
                }
            }
        } catch (ClassNotFoundException e) {
            logger.warn("Cannot re-construct original exception type. Falling back to generic StepFailedException.");
        } catch (RetryableSerDesException e) {
            throw e;
        } catch (SerDesException e) {
            logger.warn("Cannot deserialize original exception data. Falling back to generic StepFailedException.", e);
        }
        return original;
    }

    public abstract T get();
}
