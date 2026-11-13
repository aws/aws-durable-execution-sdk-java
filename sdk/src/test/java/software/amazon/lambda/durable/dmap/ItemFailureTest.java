// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.DistributedMapError;

/** Unit tests for {@link ItemFailure}. */
class ItemFailureTest {
    // The Lambda runtime serializes the response, so these assert the wire shape it produces. The mapper mirrors the
    // runtime's null exclusion, which is what keeps an absent key absent.
    private static final ObjectMapper WIRE = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static DistributedMapError error(String type, String message) {
        return new DistributedMapError(type, message, null, null);
    }

    @Test
    void itemFailureWithNullErrorMembersLeavesThemOffTheWire() throws Exception {
        var json = WIRE.writeValueAsString(new ItemFailure("1", new DistributedMapError("E", null, null, null)));

        assertEquals("{\"itemIdentifier\":\"1\",\"error\":{\"errorType\":\"E\"}}", json);
    }

    @Test
    void itemFailureNestsTheErrorObject() throws Exception {
        var json = WIRE.writeValueAsString(new ItemFailure("1", error("SomeType", "boom")));

        assertEquals(
                "{\"itemIdentifier\":\"1\",\"error\":{\"errorType\":\"SomeType\",\"errorMessage\":\"boom\"}}", json);
    }

    @Test
    void itemFailureWritesEveryErrorMember() throws Exception {
        var detail = new DistributedMapError("E", "boom", "{\"k\":1}", List.of("f1"));
        var json = WIRE.writeValueAsString(new ItemFailure("1", detail));

        assertEquals(
                "{\"itemIdentifier\":\"1\",\"error\":{\"errorType\":\"E\",\"errorMessage\":\"boom\","
                        + "\"errorData\":\"{\\\"k\\\":1}\",\"stackTrace\":[\"f1\"]}}",
                json);
    }
}
