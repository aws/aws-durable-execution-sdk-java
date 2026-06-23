// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationIdentifier;
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
        try {
            return resultSerDes.deserialize(result, resultTypeToken);
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
        var serialized = resultSerDes.serialize(result);
        var deserialized = shouldDeserializeAfterSerialization() ? deserializeResult(serialized) : result;
        return new SerializedResult<>(serialized, deserialized);
    }

    /**
     * Serializes a throwable into an {@link ErrorObject} for checkpointing.
     *
     * @param throwable the exception to serialize
     * @return the serialized error object
     */
    @SuppressWarnings("ThrowableNotThrown")
    protected ErrorObject serializeException(Throwable throwable) {
        var error = ExceptionHelper.buildErrorObject(throwable, resultSerDes);
        if (shouldDeserializeAfterSerialization()) {
            deserializeException(error);
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
                original =
                        resultSerDes.deserialize(errorData, TypeToken.get(exceptionClass.asSubclass(Throwable.class)));

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

    public abstract T get();
}
