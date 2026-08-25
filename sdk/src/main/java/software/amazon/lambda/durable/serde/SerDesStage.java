// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Objects;

/**
 * A reversible typed stage in a {@link ComposableSerDes} pipeline.
 *
 * <p>Serialization maps {@code I} to {@code O}; deserialization applies the inverse mapping. Intermediate stages may
 * use any Java types. The complete pipeline must still produce a {@link String} at its checkpoint boundary because that
 * is the persisted representation required by {@link SerDes}.
 *
 * @param <I> the stage input type during serialization
 * @param <O> the stage output type during serialization
 */
public interface SerDesStage<I, O> {
    /**
     * Applies this stage during forward serialization.
     *
     * @param value the non-null input value
     * @return the non-null transformed value
     */
    O serialize(I value);

    /**
     * Reverses this stage during deserialization.
     *
     * @param data the non-null serialized form produced by this stage
     * @return the non-null value expected by the preceding stage
     */
    I deserialize(O data);

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
    default SerDesStageResult deserializePipelineStage(O data) {
        return SerDesStageResult.continueWith(deserialize(data));
    }

    /** Returns whether this stage requires an SDK-managed durable execution context. */
    default boolean requiresDurableContext() {
        return false;
    }

    /** Returns whether this stage must be the final stage in a composable pipeline. */
    default boolean isTerminalPipelineStage() {
        return false;
    }

    /**
     * Returns an immutable stage chain that applies this stage followed by {@code nextStage} during serialization and
     * reverses them in the opposite order during deserialization.
     *
     * @param nextStage the reversible stage that consumes this stage's output
     * @param <N> the next stage's output type
     * @return the composed stage chain
     */
    default <N> SerDesStage<I, N> then(SerDesStage<? super O, N> nextStage) {
        return ChainedSerDesStage.of(this, Objects.requireNonNull(nextStage, "nextStage cannot be null"));
    }
}
