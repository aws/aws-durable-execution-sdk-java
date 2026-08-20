// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.general;

import static software.amazon.lambda.durable.logging.DurableLogger.getLogger;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.operation.DurableStepOperation.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Example demonstrating TypeToken support for complex generic types.
 *
 * <p>This example shows how to use TypeToken to work with generic types like {@code List<String>}, {@code Map<String,
 * Object>}, and nested generics that cannot be represented by simple Class objects.
 */
public class GenericTypesExample extends DurableHandler<GenericTypesExample.Input, GenericTypesExample.Output> {

    public static class Input {
        public String userId;

        public Input() {}

        public Input(String userId) {
            this.userId = userId;
        }
    }

    public static class Output {
        public List<String> items;
        public Map<String, Integer> counts;
        public Map<String, List<String>> categories;

        public Output() {}

        public Output(List<String> items, Map<String, Integer> counts, Map<String, List<String>> categories) {
            this.items = items;
            this.counts = counts;
            this.categories = categories;
        }
    }

    @Override
    public Output handleRequest(Input input) {
        getLogger().info("Starting generic types example for user: {}", input.userId);

        // Step 1: Fetch a list of items (List<String>)
        List<String> items = step("fetch-items", new TypeToken<List<String>>() {}, () -> {
            getLogger().info("Fetching items for user: {}", input.userId);
            return List.of("item1", "item2", "item3", "item4");
        });
        getLogger().info("Fetched {} items", items.size());

        // Step 2: Count items by category (Map<String, Integer>)
        Map<String, Integer> counts = step("count-by-category", new TypeToken<Map<String, Integer>>() {}, () -> {
            getLogger().info("Counting items by category");
            var result = new HashMap<String, Integer>();
            result.put("electronics", 2);
            result.put("books", 1);
            result.put("clothing", 1);
            return result;
        });
        getLogger().info("Counted {} categories", counts.size());

        // Step 3: Fetch nested generic type with retry (Map<String, List<String>>)
        Map<String, List<String>> categories = step(
                "fetch-categories",
                new TypeToken<Map<String, List<String>>>() {},
                () -> {
                    getLogger().info("Fetching category details");
                    var result = new HashMap<String, List<String>>();
                    result.put("electronics", List.of("laptop", "phone"));
                    result.put("books", List.of("fiction"));
                    result.put("clothing", List.of("shirt"));
                    return result;
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build());
        getLogger().info("Fetched {} category details", categories.size());

        getLogger().info("Generic types example completed successfully");
        return new Output(items, counts, categories);
    }
}
