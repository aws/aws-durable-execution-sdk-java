// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.map;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.operation.DurableMapOperation;
import software.amazon.lambda.durable.operation.DurableStepOperation;

/**
 * Example demonstrating the map operation with the Durable Execution SDK.
 *
 * <p>This handler processes a list of names concurrently using {@code map()}, where each item runs in its own child
 * context with full checkpoint-and-replay support.
 *
 * <ol>
 *   <li>Create a list of names from the input
 *   <li>Map over each name concurrently, applying a greeting transformation via a durable step
 *   <li>Collect and join the results
 * </ol>
 */
public class SimpleMapExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input) {
        var context = DurableContext.getCurrentContext();
        var name = input.getName();
        context.getLogger().info("Starting map example for {}", name);

        var names = List.of(name, name.toUpperCase(), name.toLowerCase());

        // Map over each name concurrently — each iteration runs in its own child context
        var result = DurableMapOperation.map("greet-all", names, String.class, item -> {
            var index = DurableMapOperation.MapItemContext.getCurrentContext().getIndex();
            return DurableStepOperation.step("greet-" + index, String.class, () -> "Hello, " + item + "!");
        });

        context.getLogger().info("Map completed: allSucceeded={}, size={}", result.allSucceeded(), result.size());

        return String.join(" | ", result.results());
    }
}
