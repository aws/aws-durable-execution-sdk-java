// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.map;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;

/**
 * Example demonstrating advanced map features: wait operations inside branches, error handling, and early termination.
 *
 * <ol>
 *   <li>Concurrent map with step + wait + step inside each branch — simulates multi-stage order processing with a
 *       cooldown between stages
 *   <li>Early termination with {@code minSuccessful(2)} — finds 2 healthy servers then stops
 * </ol>
 */
public class ComplexMapExample extends DurableHandler<Integer, String> {

    @Override
    public String handleRequest(Integer input, DurableContext context) {
        context.getLogger().info("Starting complex map example with {} items", input);

        // Part 1: Concurrent map with step + wait inside each branch
        var orderIds = IntStream.range(1, input + 1).mapToObj(x -> "order-" + x).collect(Collectors.toList());

        var orderResult = context.map("process-orders", orderIds, String.class, (orderId, index, ctx) -> {
            // Step 1: validate the order
            var validated = ctx.step("validate-" + index, String.class, stepCtx -> "validated:" + orderId);

            // Wait between stages (simulates a cooldown or external dependency)
            ctx.wait("cooldown-" + index, Duration.ofSeconds(1));

            // Step 2: finalize the order
            return ctx.step("finalize-" + index, String.class, stepCtx -> "done:" + validated);
        });

        var orderSummary = String.join(", ", orderResult.results());

        // Part 2: Early termination — find 2 healthy servers then stop
        var servers = List.of("server-1", "server-2", "server-3", "server-4", "server-5");
        var earlyTermConfig = MapConfig.builder()
                .completionConfig(CompletionConfig.minSuccessful(2))
                .build();

        var serverResult = context.map(
                "find-healthy-servers",
                servers,
                String.class,
                (server, index, ctx) -> ctx.step("health-check-" + index, String.class, stepCtx -> server + ":healthy"),
                earlyTermConfig);

        var healthyServers = serverResult.succeeded().stream().collect(Collectors.joining(", "));

        return String.format(
                "orders=[%s] | servers=[%s] reason=%s", orderSummary, healthyServers, serverResult.completionReason());
    }
}
