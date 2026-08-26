// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.internal;

import software.amazon.lambda.durable.exception.SerDesException;

/**
 * SDK-internal framing that identifies an execution input as the output of a chained-invoke SerDes pipeline.
 *
 * <p>The frame sits outside the serialized payload so the callee can distinguish it from an external invocation and
 * select the persisted SerDes pipeline without inspecting or altering the pipeline's own format.
 */
public final class ChainedInvokePayloadFrame {
    private static final String FRAME_MARKER = "__durable_execution_chained_invoke_payload:";
    private static final String FRAME_PREFIX = FRAME_MARKER + "1:";

    private ChainedInvokePayloadFrame() {}

    /** Adds the current chained-invoke frame to a non-null serialized payload. */
    public static String encode(String payload) {
        return payload == null ? null : FRAME_PREFIX + payload;
    }

    /** Returns whether the payload uses the reserved chained-invoke frame marker. */
    public static boolean isFramed(String payload) {
        return payload != null && payload.startsWith(FRAME_MARKER);
    }

    /** Removes and validates the current chained-invoke frame. */
    public static String decode(String payload) {
        if (payload == null || !payload.startsWith(FRAME_PREFIX)) {
            throw new SerDesException("Unsupported or malformed chained-invoke payload frame");
        }
        return payload.substring(FRAME_PREFIX.length());
    }
}
