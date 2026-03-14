// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.util.List;
import java.util.stream.Collectors;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Example demonstrating error handling with the map operation.
 *
 * <p>Shows how individual item failures are isolated and captured in the {@code BatchResult}, while other items
 * continue to succeed. Demonstrates inspecting partial results using {@code allSucceeded()}, {@code getError()},
 * {@code succeeded()}, and {@code failed()}.
 *
 * <ol>
 *   <li>Map over a list of order IDs concurrently
 *   <li>Some orders intentionally fail to simulate real-world partial failures
 *   <li>Inspect the BatchResult to handle successes and failures separately
 * </ol>
 */
public class MapErrorHandlingExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        var name = input.getName();
        context.getLogger().info("Starting map error handling example for {}", name);

        var orderIds = List.of("order-1", "order-INVALID", "order-3", "order-ERROR", "order-5");

        // Map over orders — some will fail, but others continue processing
        var result = context.map("process-orders", orderIds, String.class, (ctx, orderId, index) -> {
            return ctx.step(
                    "process-" + index,
                    String.class,
                    stepCtx -> {
                        if (orderId.contains("INVALID")) {
                            throw new IllegalArgumentException("Invalid order: " + orderId);
                        }
                        if (orderId.contains("ERROR")) {
                            throw new RuntimeException("Processing error for: " + orderId);
                        }
                        return "Processed " + orderId + " for " + name;
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        });

        context.getLogger()
                .info(
                        "Map completed: allSucceeded={}, succeeded={}, failed={}",
                        result.allSucceeded(),
                        result.succeeded().size(),
                        result.failed().size());

        // Build a summary showing successful results and error messages
        var successSummary = result.succeeded().stream().collect(Collectors.joining(", "));

        var errorSummary = new StringBuilder();
        for (int i = 0; i < result.size(); i++) {
            if (result.getError(i) != null) {
                errorSummary.append(
                        String.format("index %d: %s; ", i, result.getError(i).getMessage()));
            }
        }

        return String.format(
                "succeeded=%d, failed=%d | results=[%s] | errors=[%s]",
                result.succeeded().size(),
                result.failed().size(),
                successSummary,
                errorSummary.toString().trim());
    }
}
