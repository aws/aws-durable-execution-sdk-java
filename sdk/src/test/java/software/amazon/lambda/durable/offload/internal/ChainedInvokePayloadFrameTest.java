// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.PayloadOffloadException;

class ChainedInvokePayloadFrameTest {
    @Test
    void frameRoundTripsOpaquePayload() {
        var framed = ChainedInvokePayloadFrame.encode("value:with\nseparators");

        assertEquals("__durable_execution_chained_invoke_payload:1:value:value:with\nseparators", framed);
        assertTrue(ChainedInvokePayloadFrame.isFramed(framed));
        assertEquals("value:with\nseparators", ChainedInvokePayloadFrame.decode(framed));
    }

    @Test
    void nullUsesExplicitFrame() {
        var framed = ChainedInvokePayloadFrame.encode(null);

        assertEquals("__durable_execution_chained_invoke_payload:1:null", framed);
        assertTrue(ChainedInvokePayloadFrame.isFramed(framed));
        assertNull(ChainedInvokePayloadFrame.decode(framed));
    }

    @Test
    void unsupportedFrameFailsClosed() {
        assertThrows(
                PayloadOffloadException.class,
                () -> ChainedInvokePayloadFrame.decode("__durable_execution_chained_invoke_payload:2:value"));
    }
}
