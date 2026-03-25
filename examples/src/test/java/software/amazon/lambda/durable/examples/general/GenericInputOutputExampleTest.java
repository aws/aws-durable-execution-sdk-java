// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class GenericInputOutputExampleTest {

    private static final TypeToken<Map<String, Map<String, List<String>>>> resultType = new TypeToken<>() {};
    private static final TypeToken<Map<String, String>> inputType = new TypeToken<>() {};

    @Test
    void testGenericTypesExample() {
        var handler = new GenericInputOutputExample();
        var runner = LocalDurableTestRunner.create(inputType, handler);

        var input = new HashMap<>(Map.of("userId", "user123"));
        var result = runner.run(input);

        assertNotNull(result);
        var output = result.getResult(resultType);
        assertNotNull(output);

        // Verify categories nested map
        var categories = output.get("categories");
        assertNotNull(categories);
        assertEquals(3, categories.size());
        assertEquals(2, categories.get("electronics").size());
        assertTrue(categories.get("electronics").contains("laptop"));
        assertTrue(categories.get("electronics").contains("phone"));
        assertEquals(1, categories.get("books").size());
        assertTrue(categories.get("books").contains("fiction"));
    }

    @Test
    void testOperationTracking() {
        var handler = new GenericInputOutputExample();
        var runner = LocalDurableTestRunner.create(inputType, handler);

        var input = new HashMap<>(Map.of("userId", "user123"));
        var result = runner.run(input);

        // Verify all operations were executed
        var fetchCategories = result.getOperation("fetch-categories");
        assertNotNull(fetchCategories);
        assertEquals("fetch-categories", fetchCategories.getName());
    }
}
