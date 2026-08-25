// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Objects;

/**
 * Result returned when a {@link SerDesStage} is reversed in a {@link ComposableSerDes}.
 *
 * @param value the non-null value produced by the stage
 * @param skipRemainingStages whether deserialization should skip the remaining intermediate stages and decode
 *     {@code value} directly with the pipeline's value codec
 */
public record SerDesStageResult(Object value, boolean skipRemainingStages) {
    public SerDesStageResult {
        Objects.requireNonNull(value, "value cannot be null");
    }

    /** Continues reverse processing through the remaining intermediate stages. */
    public static SerDesStageResult continueWith(Object value) {
        return new SerDesStageResult(value, false);
    }

    /** Skips the remaining intermediate stages and decodes the value directly with the pipeline's value codec. */
    public static SerDesStageResult decodeWithValueCodec(String value) {
        return new SerDesStageResult(value, true);
    }
}
