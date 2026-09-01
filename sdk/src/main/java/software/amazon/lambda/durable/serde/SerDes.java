// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.lambda.durable.TypeToken;

/**
 * Interface for serialization and deserialization of objects.
 *
 * <p>Implementations must support both simple types via {@link Class} and complex generic types via {@link TypeToken}.
 *
 * <p>An implementation that publishes payloads to external storage must return an immutable or versioned reference for
 * every serialized value. It must not overwrite content reachable through a string that may already be stored in a
 * durable checkpoint, because replay can occur after a later serialization attempt fails to checkpoint.
 */
public interface SerDes {
    /**
     * Serializes an object to a JSON string.
     *
     * @param value the object to serialize
     * @return the JSON string representation, or null if value is null
     */
    String serialize(Object value);

    /**
     * Serializes an object with durable payload context.
     *
     * <p>The default implementation preserves compatibility with existing SerDes implementations by delegating to
     * {@link #serialize(Object)}.
     *
     * @param value the object to serialize
     * @param context durable payload identity supplied by the SDK
     * @return the serialized string, or null if value is null
     */
    default String serialize(Object value, SerDesContext context) {
        return serialize(value);
    }

    /**
     * Deserializes a JSON string to an object of the specified generic type.
     *
     * <p>This method supports complex generic types like {@code List<MyObject>} or {@code Map<String, MyObject>} that
     * cannot be represented by a simple {@link Class} object.
     *
     * <p>Usage example:
     *
     * <pre>{@code
     * List<String> items = serDes.deserialize(json, new TypeToken<List<String>>() {});
     * }</pre>
     *
     * @param data the JSON string to deserialize
     * @param typeToken the type token capturing the generic type information
     * @param <T> the target type
     * @return the deserialized object, or null if data is null
     */
    <T> T deserialize(String data, TypeToken<T> typeToken);

    /**
     * Deserializes a string with durable payload context.
     *
     * <p>The default implementation preserves compatibility with existing SerDes implementations by delegating to
     * {@link #deserialize(String, TypeToken)}.
     *
     * @param data the string to deserialize
     * @param typeToken target type information
     * @param context durable payload identity supplied by the SDK
     * @param <T> target type
     * @return the deserialized value, or null if data is null
     */
    default <T> T deserialize(String data, TypeToken<T> typeToken, SerDesContext context) {
        return deserialize(data, typeToken);
    }
}
