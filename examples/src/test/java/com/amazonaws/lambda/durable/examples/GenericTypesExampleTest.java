// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

class GenericTypesExampleTest {

    @Test
    void testGenericTypesExample() {
        var handler = new GenericTypesExample();
        var runner = LocalDurableTestRunner.create(GenericTypesExample.Input.class, handler)
                .withSkipTime(true);

        var input = new GenericTypesExample.Input("user123");
        var result = runner.run(input);

        assertNotNull(result);
        GenericTypesExample.Output output = result.getResult(GenericTypesExample.Output.class);
        assertNotNull(output);

        // Verify items list
        assertNotNull(output.items);
        assertEquals(4, output.items.size());
        assertTrue(output.items.contains("item1"));
        assertTrue(output.items.contains("item4"));

        // Verify counts map
        assertNotNull(output.counts);
        assertEquals(3, output.counts.size());
        assertEquals(2, output.counts.get("electronics"));
        assertEquals(1, output.counts.get("books"));
        assertEquals(1, output.counts.get("clothing"));

        // Verify categories nested map
        assertNotNull(output.categories);
        assertEquals(3, output.categories.size());
        assertEquals(2, output.categories.get("electronics").size());
        assertTrue(output.categories.get("electronics").contains("laptop"));
        assertTrue(output.categories.get("electronics").contains("phone"));
        assertEquals(1, output.categories.get("books").size());
        assertTrue(output.categories.get("books").contains("fiction"));
    }

    @Test
    void testOperationTracking() {
        var handler = new GenericTypesExample();
        var runner = LocalDurableTestRunner.create(GenericTypesExample.Input.class, handler)
                .withSkipTime(true);

        var input = new GenericTypesExample.Input("user456");
        var result = runner.run(input);

        // Verify all operations were executed
        var fetchItems = result.getOperation("fetch-items");
        assertNotNull(fetchItems);
        assertEquals("fetch-items", fetchItems.getName());

        var countByCategory = result.getOperation("count-by-category");
        assertNotNull(countByCategory);
        assertEquals("count-by-category", countByCategory.getName());

        var fetchCategories = result.getOperation("fetch-categories");
        assertNotNull(fetchCategories);
        assertEquals("fetch-categories", fetchCategories.getName());
    }
}
