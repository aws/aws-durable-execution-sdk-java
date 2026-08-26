// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.SerDesException;

class ChainedInvokePayloadFrameTest {

    @Test
    void roundTripsSerializedPayloadWithoutReencodingIt() {
        var payload = "{\"file\":\"/mnt/efs/payload\"}";

        var framed = ChainedInvokePayloadFrame.encode(payload);

        assertTrue(ChainedInvokePayloadFrame.isFramed(framed));
        assertEquals(payload, ChainedInvokePayloadFrame.decode(framed));
    }

    @Test
    void nullAndExternalPayloadsAreNotFramed() {
        assertNull(ChainedInvokePayloadFrame.encode(null));
        assertFalse(ChainedInvokePayloadFrame.isFramed(null));
        assertFalse(ChainedInvokePayloadFrame.isFramed("{\"external\":true}"));
    }

    @Test
    void rejectsUnsupportedOrMalformedReservedFrames() {
        assertThrows(
                SerDesException.class,
                () -> ChainedInvokePayloadFrame.decode("__durable_execution_chained_invoke_payload:2:value"));
        assertThrows(SerDesException.class, () -> ChainedInvokePayloadFrame.decode("external"));
    }
}
