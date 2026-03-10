// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
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
public class ManyAsyncChildContextExample extends DurableHandler<ManyAsyncChildContextExample.Input, String> {

    private static final int STEP_COUNT = 500;

    public record Input(int multiplier) {}

    @Override
    public String handleRequest(Input input, DurableContext context) {
        var startTime = System.nanoTime();
        var multiplier = input.multiplier() > 0 ? input.multiplier() : 1;

        context.getLogger().info("Starting {} async child context with multiplier {}", STEP_COUNT, multiplier);

        // Create async steps
        var futures = new ArrayList<DurableFuture<Integer>>(STEP_COUNT);
        for (var i = 0; i < STEP_COUNT; i++) {
            var index = i;
            var future = context.runInChildContextAsync("child-" + i, Integer.class, childCtx -> {
                // create a step inside the child context, which doubles the number of threads
                return childCtx.step("compute-" + index, Integer.class, stepCtx -> index * multiplier);
            });
            futures.add(future);
        }

        context.getLogger().info("All {} async child context created, collecting results", STEP_COUNT);

        // Collect all results using allOf
        var results = DurableFuture.allOf(futures);
        var totalSum = results.stream().mapToInt(Integer::intValue).sum();

        // checkpoint the executionTime so that we can have the same value when replay
        var executionTimeMs = context.step(
                "execution-time", Long.class, stepCtx -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));
        context.getLogger()
                .info(
                        "Completed {} child context, total sum: {}, execution time: {}ms",
                        STEP_COUNT,
                        totalSum,
                        executionTimeMs);

        // Wait 10 seconds to test replay
        context.wait("post-compute-wait", Duration.ofSeconds(10));

        var replayTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        return String.format(
                "Completed %d async child context. Sum: %d, Execution Time: %dms, Replay Time: %dms",
                STEP_COUNT, totalSum, executionTimeMs, replayTimeMs);
    }
}
