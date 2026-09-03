// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.internal;

import software.amazon.lambda.durable.exception.PayloadOffloadException;

/** Versioned source frame for chained-invoke payloads produced through the persisted payload offloader. */
public final class ChainedInvokePayloadFrame {
    private static final String FRAME_MARKER = "__durable_execution_chained_invoke_payload:";
    private static final String FRAME_PREFIX = FRAME_MARKER + "1:";
    private static final String NULL_FRAME = FRAME_PREFIX + "null";
    private static final String VALUE_PREFIX = FRAME_PREFIX + "value:";

    private ChainedInvokePayloadFrame() {}

    public static String encode(String payload) {
        return payload == null ? NULL_FRAME : VALUE_PREFIX + payload;
    }

    public static boolean isFramed(String payload) {
        return payload != null && payload.startsWith(FRAME_MARKER);
    }

    public static String decode(String payload) {
        if (NULL_FRAME.equals(payload)) {
            return null;
        }
        if (payload != null && payload.startsWith(VALUE_PREFIX)) {
            return payload.substring(VALUE_PREFIX.length());
        }
        throw new PayloadOffloadException("Unsupported or malformed chained-invoke payload frame");
    }
}
