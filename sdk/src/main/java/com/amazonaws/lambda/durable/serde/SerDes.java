// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.serde;

import com.amazonaws.lambda.durable.TypeToken;

/**
 * Interface for serialization and deserialization of objects.
 *
 * <p>Implementations must support both simple types via {@link Class} and complex generic types via {@link TypeToken}.
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
     * Deserializes a JSON string to an object of the specified type.
     *
     * @param data the JSON string to deserialize
     * @param type the class of the target type
     * @param <T> the target type
     * @return the deserialized object, or null if data is null
     */
    <T> T deserialize(String data, Class<T> type);

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
}
