// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

/**
 * Interface for serialization and deserialization of objects at the persisted string boundary.
 *
 * <p>Implementations can also be used as string-producing stages in a {@link ComposableSerDes}. Use {@link SerDesStage}
 * for transformations that consume and produce strings.
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
     * Deserializes this SerDes when it is used as an intermediate pipeline stage.
     *
     * <p>The default uses {@link String} as the intermediate value type, preserving existing SerDes behavior.
     */
    default SerDesStageResult deserializePipelineStage(String data) {
        var result = deserialize(data, TypeToken.get(String.class));
        if (result == null) {
            throw new SerDesException("Stage returned null for non-null data");
        }
        return SerDesStageResult.continueWith(result);
    }

    /**
     * Returns whether this SerDes requires an SDK-managed durable execution context.
     *
     * <p>Context-dependent SerDes implementations cannot process an initial external invocation payload unless a
     * separate context-free input SerDes is configured.
     */
    default boolean requiresDurableContext() {
        return false;
    }

    /**
     * Returns whether this SerDes must be the final stage in a composable pipeline.
     *
     * <p>Stages that make size-based storage decisions should normally be terminal so later transformations cannot
     * expand their output beyond checkpoint limits.
     */
    default boolean isTerminalPipelineStage() {
        return false;
    }

    /**
     * Returns whether this SerDes performs only value-codec processing without additional composable pipeline stages.
     *
     * <p>SerDes decorators should delegate this capability to their wrapped SerDes.
     */
    default boolean isValueCodecOnly() {
        return true;
    }

    /**
     * Returns an immutable processing pipeline that invokes this SerDes followed by {@code nextStage} when serializing
     * and in reverse order when deserializing.
     *
     * <p>This SerDes is the value codec. Intermediate stages may transform values into arbitrary Java types, but the
     * final stage must return a string for persistence.
     *
     * @param nextStage the reversible stage to append
     * @return a composable SerDes pipeline
     */
    default ComposableSerDes then(SerDes nextStage) {
        return ComposableSerDes.of(this, nextStage);
    }

    /**
     * Returns an immutable processing pipeline with a string stage appended.
     *
     * @param nextStage the reversible string stage to append
     * @return a composable SerDes pipeline
     */
    default ComposableSerDes then(SerDesStage nextStage) {
        return ComposableSerDes.builder(this).then(nextStage).build();
    }
}
