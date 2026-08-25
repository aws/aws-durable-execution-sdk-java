// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/**
 * A reversible string stage in a {@link ComposableSerDes} pipeline.
 *
 * <p>Every top-level stage consumes and produces a string, so stages can be composed in any order without intermediate
 * type mismatches. Use {@link ComposableBinarySerDesStage} to perform an efficient chain of binary transformations
 * inside one string stage.
 */
public interface SerDesStage {
    /**
     * Applies this stage during forward serialization.
     *
     * @param value the non-null input string
     * @return the non-null transformed string
     */
    String serialize(String value);

    /**
     * Reverses this stage during deserialization.
     *
     * @param data the non-null serialized string produced by this stage
     * @return the non-null string expected by the preceding stage
     */
    String deserialize(String data);

    /**
     * Reverses this stage with control over external-boundary processing.
     *
     * <p>Most stages should use the default result. Boundary stages may return
     * {@link SerDesStageResult#decodeWithValueCodec(String)} when the input originated outside the configured pipeline
     * and should bypass the remaining intermediate stages.
     *
     * @param data the non-null serialized form produced by this stage
     * @return the stage result
     */
    default SerDesStageResult deserializePipelineStage(String data) {
        return SerDesStageResult.continueWith(deserialize(data));
    }
}
