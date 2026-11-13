// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ItemResult}. */
class ItemResultTest {
    // The Lambda runtime serializes the response, so these assert the wire shape it produces. The mapper mirrors the
    // runtime's null exclusion, which is what keeps an absent key absent.
    private static final ObjectMapper WIRE = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void itemResultWithNoOutputLeavesTheOutputOffTheWire() throws Exception {
        var json = WIRE.writeValueAsString(new ItemResult("0", null));

        assertEquals("{\"itemIdentifier\":\"0\"}", json);
    }
}
