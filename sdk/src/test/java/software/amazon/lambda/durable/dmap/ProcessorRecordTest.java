// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/** Unit tests for {@link ProcessorRecord}. */
class ProcessorRecordTest {
    @Test
    void processorRecordWithoutBodyIsRejected() {
        var exception = assertThrows(IllegalStateException.class, () -> new ProcessorRecord("a", null));
        assertEquals("expected each distributed map processor record to have a 'body'", exception.getMessage());
    }

    @Test
    void processorRecordWithEmptyBodyIsAccepted() {
        assertEquals("", new ProcessorRecord("a", "").body());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void processorRecordWithoutItemIdIsRejected(String itemId) {
        var exception = assertThrows(IllegalStateException.class, () -> new ProcessorRecord(itemId, "1"));
        assertEquals("expected each distributed map processor record to have an 'itemId'", exception.getMessage());
    }
}
