// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.util.List;
import java.util.stream.Collectors;
import software.amazon.lambda.durable.CompletionConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.MapConfig;

/**
 * Example demonstrating MapConfig options: concurrency limiting and completion strategies.
 *
 * <p>This handler runs two map operations to showcase different configurations:
 *
 * <ol>
 *   <li>Sequential processing with {@code maxConcurrency(1)} — items run one at a time
 *   <li>Early termination with {@code minSuccessful(2)} — stops after 2 items succeed
 * </ol>
 */
public class MapConfigExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        var name = input.getName();
        context.getLogger().info("Starting map config example for {}", name);

        // Part 1: Sequential execution with maxConcurrency=1
        var items = List.of("alpha", "beta", "gamma");
        var sequentialConfig = MapConfig.builder().maxConcurrency(1).build();

        var sequentialResult = context.map(
                "sequential-processing",
                items,
                String.class,
                (ctx, item, index) -> {
                    return ctx.step("transform-" + index, String.class, stepCtx -> item.toUpperCase() + "-" + name);
                },
                sequentialConfig);

        var sequentialOutput = String.join(", ", sequentialResult.results());
        context.getLogger().info("Sequential result: {}", sequentialOutput);

        // Part 2: Early termination with minSuccessful(2)
        var candidates = List.of("server-1", "server-2", "server-3", "server-4", "server-5");
        var earlyTermConfig = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.minSuccessful(2))
                .build();

        var earlyTermResult = context.map(
                "find-healthy-servers",
                candidates,
                String.class,
                (ctx, server, index) -> {
                    return ctx.step("health-check-" + index, String.class, stepCtx -> server + ":healthy");
                },
                earlyTermConfig);

        context.getLogger()
                .info(
                        "Early termination: reason={}, succeeded={}",
                        earlyTermResult.completionReason(),
                        earlyTermResult.succeeded().size());

        var healthyServers = earlyTermResult.succeeded().stream().collect(Collectors.joining(", "));

        return String.format(
                "sequential=[%s] | earlyTerm=[%s] reason=%s",
                sequentialOutput, healthyServers, earlyTermResult.completionReason());
    }
}
