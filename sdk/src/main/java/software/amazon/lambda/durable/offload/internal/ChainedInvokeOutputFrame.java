// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.internal;

import software.amazon.lambda.durable.exception.PayloadOffloadException;

/** Versioned frame identifying results and errors returned by a compatible durable chained-invoke target. */
public final class ChainedInvokeOutputFrame {
    private static final String FRAME_MARKER = "__durable_execution_chained_invoke_output:";
    private static final String FRAME_PREFIX = FRAME_MARKER + "1:";
    private static final String CODEC_PREFIX = FRAME_PREFIX + "codec:";
    private static final String RAW_PREFIX = FRAME_PREFIX + "raw:";

    private ChainedInvokeOutputFrame() {}

    public static String encode(String payload, boolean usesPayloadCodec) {
        if (payload == null) {
            return null;
        }
        return (usesPayloadCodec ? CODEC_PREFIX : RAW_PREFIX) + payload;
    }

    public static boolean isFramed(String payload) {
        return payload != null && payload.startsWith(FRAME_MARKER);
    }

    public static Decoded decode(String payload) {
        if (payload != null && payload.startsWith(CODEC_PREFIX)) {
            return new Decoded(payload.substring(CODEC_PREFIX.length()), true);
        }
        if (payload != null && payload.startsWith(RAW_PREFIX)) {
            return new Decoded(payload.substring(RAW_PREFIX.length()), false);
        }
        throw new PayloadOffloadException("Unsupported or malformed chained-invoke output frame");
    }

    /** Decoded chained-invoke output and whether its payload uses the SDK payload codec. */
    public record Decoded(String payload, boolean usesPayloadCodec) {}
}
