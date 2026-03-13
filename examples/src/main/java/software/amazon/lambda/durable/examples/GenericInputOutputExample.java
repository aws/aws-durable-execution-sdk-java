// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Example demonstrating a durable Lambda function that uses generic types in input and output.
 *
 * <p>This example shows how to use TypeToken to work with generic types like {@code List<String>}, {@code Map<String,
 * List<String>>}, and nested generics that cannot be represented by simple Class objects.
 */
public class GenericInputOutputExample
        extends DurableHandler<Map<String, String>, Map<String, Map<String, List<String>>>> {

    private static final Logger logger = LoggerFactory.getLogger(GenericInputOutputExample.class);

    @Override
    public Map<String, Map<String, List<String>>> handleRequest(Map<String, String> input, DurableContext context) {
        logger.info("Starting generic types example for user: {}", input.get("userId"));

        // Fetch nested generic type with retry (Map<String, List<String>>)
        Map<String, List<String>> categories = context.step(
                "fetch-categories",
                new TypeToken<Map<String, List<String>>>() {},
                stepCtx -> {
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

        // return a result of Map<String, Map<String, List<String>>>
        return new HashMap<>(Map.of("categories", categories));
    }
}
