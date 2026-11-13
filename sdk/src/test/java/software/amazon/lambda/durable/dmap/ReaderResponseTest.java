// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ReaderResponse}. */
class ReaderResponseTest {
    // The Lambda runtime serializes the response, so these assert the wire shape it produces. The mapper mirrors the
    // runtime's null exclusion, which is what keeps an absent key absent.
    private static final ObjectMapper WIRE = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void exhaustedReaderPageLeavesNextStateOffTheWire() throws Exception {
        var json = WIRE.writeValueAsString(new ReaderResponse(List.of("1", "2"), null));

        assertEquals("{\"items\":[\"1\",\"2\"]}", json);
    }
}
