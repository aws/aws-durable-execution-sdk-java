// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

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
     * Deserializes this SerDes when it is used as a string-processing pipeline stage.
     *
     * <p>Most stages should use the default result, which continues reverse processing through earlier string stages.
     * Boundary stages may return {@link SerDesStageResult#decodeWithValueCodec(String)} when the input originated
     * outside the configured pipeline and must be decoded directly by the value codec.
     *
     * @param data the non-null string supplied to this stage
     * @return the stage result
     */
    default SerDesStageResult deserializePipelineStage(String data) {
        Object result = deserialize(data, TypeToken.get(String.class));
        if (result == null) {
            throw new SerDesException("Stage returned null for non-null data");
        }
        if (!(result instanceof String stringResult)) {
            throw new SerDesException("String stage returned a non-string value");
        }
        return SerDesStageResult.continueWith(stringResult);
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
     * Returns an immutable processing pipeline that invokes this SerDes followed by {@code nextStage} when serializing
     * and in reverse order when deserializing.
     *
     * <p>This SerDes is the value codec. The next stage must accept and return strings.
     *
     * @param nextStage the reversible string-processing stage to append
     * @return a composable SerDes pipeline
     */
    default ComposableSerDes then(SerDes nextStage) {
        return ComposableSerDes.of(this, nextStage);
    }
}
