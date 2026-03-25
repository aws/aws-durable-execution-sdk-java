// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.map;

import java.time.Duration;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.serde.JacksonSerDes;

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
public class DeserializationFailedMapExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        var name = input.getName();
        context.getLogger().info("Starting map example for {}", name);

        var names = List.of(name, name.toUpperCase(), name.toLowerCase());

        // Map over each name concurrently — each iteration runs in its own child context
        var result = context.map(
                "greet-all",
                names,
                String.class,
                (item, index, ctx) -> {
                    return ctx.step("greet-" + index, String.class, stepCtx -> {
                        throw new RuntimeException("Failure from " + item + "!");
                    });
                },
                MapConfig.builder().serDes(new FailedSerDes()).build());

        context.getLogger().info("Map completed: allSucceeded={}, size={}", result.allSucceeded(), result.size());

        context.wait("suspend and replay", Duration.ofSeconds(1));

        return result.getError(0).errorMessage();
    }

    private static class FailedSerDes extends JacksonSerDes {

        @Override
        public <T> T deserialize(String json, TypeToken<T> typeToken) {
            T result = super.deserialize(json, typeToken);
            if (result instanceof RuntimeException ex) {
                throw new SerDesException("Deserialization failed", ex);
            }
            return result;
        }
    }
}
