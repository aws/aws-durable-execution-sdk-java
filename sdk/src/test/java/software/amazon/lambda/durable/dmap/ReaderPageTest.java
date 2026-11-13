// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ReaderPage}. */
class ReaderPageTest {
    @Test
    void nullItemsGiveAnEmptyList() {
        var page = new ReaderPage<Integer, Integer>(null, 1);

        assertEquals(List.of(), page.items());
    }

    @Test
    void changingTheSourceListDoesNotChangeItems() {
        var source = new ArrayList<>(List.of(1, 2));
        var page = new ReaderPage<>(source, null);

        source.add(3);

        assertEquals(List.of(1, 2), page.items());
    }
}
