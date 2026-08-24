// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Objects;

/**
 * Result returned when a {@link SerDes} is used as a string-processing stage in a {@link ComposableSerDes}.
 *
 * @param value the string produced by the stage
 * @param skipRemainingStages whether deserialization should skip the remaining string stages and decode {@code value}
 *     directly with the pipeline's value codec
 */
public record SerDesStageResult(String value, boolean skipRemainingStages) {
    public SerDesStageResult {
        Objects.requireNonNull(value, "value cannot be null");
    }

    /** Continues reverse processing through the remaining string stages. */
    public static SerDesStageResult continueWith(String value) {
        return new SerDesStageResult(value, false);
    }

    /** Skips the remaining string stages and decodes the value directly with the pipeline's value codec. */
    public static SerDesStageResult decodeWithValueCodec(String value) {
        return new SerDesStageResult(value, true);
    }
}
