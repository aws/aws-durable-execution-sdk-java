// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OffloadedPayloadTest {
    @Test
    @SuppressWarnings("unchecked")
    void previewArraysAreDetachedAndNormalizedToImmutableLists() {
        var tags = new String[] {"original", "stable"};
        var counts = new int[] {1, 2};
        var preview = new LinkedHashMap<String, Object>();
        preview.put("tags", tags);
        preview.put("nested", Map.of("counts", counts));

        var payload = OffloadedPayload.reference("memory://payload", preview);
        tags[0] = "changed";
        counts[0] = 99;

        var storedTags = (List<Object>) payload.preview().get("tags");
        var nested = (Map<String, Object>) payload.preview().get("nested");
        var storedCounts = (List<Object>) nested.get("counts");
        assertEquals(List.of("original", "stable"), storedTags);
        assertEquals(List.of(1, 2), storedCounts);
        assertThrows(UnsupportedOperationException.class, () -> storedTags.add("later"));
        assertThrows(UnsupportedOperationException.class, () -> storedCounts.set(0, 99));
    }

    @Test
    void mutableScalarLikeValuesAreSnapshottedOrRejected() {
        var text = new StringBuilder("original");
        var payload = OffloadedPayload.reference("memory://payload", Map.of("text", text));
        text.append("-changed");

        assertEquals("original", payload.preview().get("text"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OffloadedPayload.reference("memory://payload", Map.of("unsupported", new java.util.Date())));
    }
}
