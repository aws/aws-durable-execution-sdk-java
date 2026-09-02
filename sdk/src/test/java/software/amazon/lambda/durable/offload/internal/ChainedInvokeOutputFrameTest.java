// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.PayloadOffloadException;

class ChainedInvokeOutputFrameTest {
    @Test
    void codecAndRawPayloadsRoundTrip() {
        var codec = ChainedInvokeOutputFrame.encode("@aws-durable-payload:v1:value", true);
        var raw = ChainedInvokeOutputFrame.encode("@aws-durable-payload:v2:external", false);

        assertEquals("__durable_execution_chained_invoke_output:1:codec:@aws-durable-payload:v1:value", codec);
        assertEquals("__durable_execution_chained_invoke_output:1:raw:@aws-durable-payload:v2:external", raw);
        assertTrue(ChainedInvokeOutputFrame.isFramed(codec));
        assertTrue(ChainedInvokeOutputFrame.decode(codec).usesPayloadCodec());
        assertEquals(
                "@aws-durable-payload:v1:value",
                ChainedInvokeOutputFrame.decode(codec).payload());
        assertFalse(ChainedInvokeOutputFrame.decode(raw).usesPayloadCodec());
        assertEquals(
                "@aws-durable-payload:v2:external",
                ChainedInvokeOutputFrame.decode(raw).payload());
    }

    @Test
    void nullRemainsUnframed() {
        assertNull(ChainedInvokeOutputFrame.encode(null, true));
        assertFalse(ChainedInvokeOutputFrame.isFramed(null));
    }

    @Test
    void unsupportedVersionFailsClosed() {
        assertThrows(
                PayloadOffloadException.class,
                () -> ChainedInvokeOutputFrame.decode("__durable_execution_chained_invoke_output:2:raw:value"));
    }
}
