// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BatchContext}. */
class BatchContextTest {
    @Test
    void changingTheSourceListDoesNotChangeItemIds() {
        var source = new ArrayList<>(List.of("a", "b"));
        var batchContext = new BatchContext("arn", source);

        source.add("c");

        assertEquals(List.of("a", "b"), batchContext.itemIds());
    }
}
