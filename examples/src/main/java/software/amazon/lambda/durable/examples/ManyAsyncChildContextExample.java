// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Performance test example demonstrating concurrent async child contexts.
 *
 * <p>This example tests the SDK's ability to handle many concurrent operations:
 *
 * <ul>
 *   <li>Creates async child context in a loop
 *   <li>Each child context performs a simple computation in a step
 *   <li>All results are collected using {@link DurableFuture#allOf}
 * </ul>
 */
public class ManyAsyncChildContextExample
        extends DurableHandler<ManyAsyncChildContextExample.Input, ManyAsyncChildContextExample.Output> {

    public record Input(int multiplier, int steps) {}

    public record Output(long result, long executionTimeMs, long replayTimeMs) {}

    @Override
    public Output handleRequest(Input input, DurableContext context) {
        var startTime = System.nanoTime();
        var multiplier = input.multiplier();
        var steps = input.steps();
        var logger = context.getLogger();

        logger.info("Starting {} async child context with multiplier {}", steps, multiplier);

        // Create async steps
        var futures = new ArrayList<DurableFuture<Integer>>(steps);
        for (var i = 0; i < steps; i++) {
            var index = i;
            var future = context.runInChildContextAsync("child-" + i, Integer.class, childCtx -> {
                // create a step inside the child context, which doubles the number of threads
                return childCtx.step("compute-" + index, Integer.class, stepCtx -> index * multiplier);
            });
            futures.add(future);
        }

        logger.info("All {} async child context created, collecting results", steps);

        // Collect all results using allOf
        var results = DurableFuture.allOf(futures);
        var totalSum = results.stream().mapToInt(Integer::intValue).sum();

        // checkpoint the executionTime so that we can have the same value when replay
        var executionTimeMs = context.step(
                "execution-time", Long.class, stepCtx -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));
        logger.info(
                "Completed {} child context, total sum: {}, execution time: {}ms", steps, totalSum, executionTimeMs);

        // Wait 2 seconds to test replay
        context.wait("post-compute-wait", Duration.ofSeconds(2));

        var replayTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        return new Output(totalSum, executionTimeMs, replayTimeMs);
    }

    @Override
    protected DurableConfig createConfiguration() {
        // Add a small checkpoint delay to help batch the checkpoint requests and reduce the overall latencies
        // when the function has many concurrent operations
        return DurableConfig.builder()
                .withCheckpointDelay(Duration.ofMillis(10))
                .build();
    }
}
