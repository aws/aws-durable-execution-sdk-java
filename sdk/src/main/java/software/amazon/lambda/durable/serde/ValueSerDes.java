// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * Converts domain values to and from a typed pipeline representation.
 *
 * <p>The representation may be any Java type. A {@link ComposableSerDes} connects it to a {@link SerDesStage} chain
 * whose final serialized representation is the checkpoint {@link String}.
 *
 * @param <R> the serialized representation produced for the first pipeline stage
 */
public interface ValueSerDes<R> {
    /**
     * Serializes a domain value to the representation consumed by the first pipeline stage.
     *
     * @param value the domain value
     * @return the serialized representation, or null if value is null
     */
    R serialize(Object value);

    /**
     * Deserializes the representation to the requested domain type.
     *
     * @param data the representation restored by the stage chain
     * @param typeToken the requested domain type
     * @param <T> the requested domain type
     * @return the deserialized value, or null if data is null
     */
    <T> T deserialize(R data, TypeToken<T> typeToken);

    /**
     * Deserializes an unframed external string that did not pass through the configured stage chain.
     *
     * <p>Codecs that can receive external invocation, callback, or invoke-result payloads should override this method.
     * String-valued {@link SerDes} implementations support it automatically.
     *
     * @param data the external string payload
     * @param typeToken the requested domain type
     * @param <T> the requested domain type
     * @return the deserialized value
     */
    default <T> T deserializeExternal(String data, TypeToken<T> typeToken) {
        throw new SerDesException(
                "Value SerDes " + getClass().getName() + " cannot deserialize an external String payload");
    }

    /** Returns whether this value SerDes requires an SDK-managed durable execution context. */
    default boolean requiresDurableContext() {
        return false;
    }

    /** Returns whether this value SerDes must be the final component of a composable pipeline. */
    default boolean isTerminalPipelineStage() {
        return false;
    }
}
