// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating TypeToken support for complex generic types.
 *
 * <p>This example shows how to use TypeToken to work with generic types like List<String>, Map<String, Object>, and
 * nested generics that cannot be represented by simple Class objects.
 */
public class GenericTypesExample extends DurableHandler<GenericTypesExample.Input, GenericTypesExample.Output> {

    private static final Logger logger = LoggerFactory.getLogger(GenericTypesExample.class);

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
    public Output handleRequest(Input input, DurableContext context) {
        logger.info("Starting generic types example for user: {}", input.userId);

        // Step 1: Fetch a list of items (List<String>)
        List<String> items = context.step("fetch-items", new TypeToken<List<String>>() {}, () -> {
            logger.info("Fetching items for user: {}", input.userId);
            return List.of("item1", "item2", "item3", "item4");
        });
        logger.info("Fetched {} items", items.size());

        // Step 2: Count items by category (Map<String, Integer>)
        Map<String, Integer> counts =
                context.step("count-by-category", new TypeToken<Map<String, Integer>>() {}, () -> {
                    logger.info("Counting items by category");
                    var result = new HashMap<String, Integer>();
                    result.put("electronics", 2);
                    result.put("books", 1);
                    result.put("clothing", 1);
                    return result;
                });
        logger.info("Counted {} categories", counts.size());

        // Step 3: Fetch nested generic type with retry (Map<String, List<String>>)
        Map<String, List<String>> categories = context.step(
                "fetch-categories",
                new TypeToken<Map<String, List<String>>>() {},
                () -> {
                    logger.info("Fetching category details");
                    var result = new HashMap<String, List<String>>();
                    result.put("electronics", List.of("laptop", "phone"));
                    result.put("books", List.of("fiction"));
                    result.put("clothing", List.of("shirt"));
                    return result;
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build());
        logger.info("Fetched {} category details", categories.size());

        logger.info("Generic types example completed successfully");
        return new Output(items, counts, categories);
    }
}
