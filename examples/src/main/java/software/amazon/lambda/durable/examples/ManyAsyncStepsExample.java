// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import java.util.ArrayList;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Performance test example demonstrating concurrent async steps.
 *
 * <p>This example tests the SDK's ability to handle many concurrent operations:
 *
 * <ul>
 *   <li>Creates async steps in a loop
 *   <li>Each step performs a simple computation
 *   <li>All results are collected using {@link DurableFuture#allOf}
 * </ul>
 */
public class ManyAsyncStepsExample extends DurableHandler<ManyAsyncStepsExample.Input, String> {

    private static final int STEP_COUNT = 500;

    public record Input(int multiplier) {}

    @Override
    public String handleRequest(Input input, DurableContext context) {
        var startTime = System.currentTimeMillis();
        var multiplier = input.multiplier() > 0 ? input.multiplier() : 1;

        context.getLogger().info("Starting {} async steps with multiplier {}", STEP_COUNT, multiplier);

        // Create async steps
        var futures = new ArrayList<DurableFuture<Integer>>(STEP_COUNT);
        for (var i = 0; i < STEP_COUNT; i++) {
            var index = i;
            var future = context.stepAsync("compute-" + i, Integer.class, () -> index * multiplier);
            futures.add(future);
        }

        context.getLogger().info("All {} async steps created, collecting results", STEP_COUNT);

        // Collect all results using allOf
        var results = DurableFuture.allOf(futures);
        var totalSum = results.stream().mapToInt(Integer::intValue).sum();

        var executionTimeMs = System.currentTimeMillis() - startTime;
        context.getLogger()
                .info("Completed {} steps, total sum: {}, execution time: {}ms", STEP_COUNT, totalSum, executionTimeMs);

        // Wait 10 seconds to test replay
        context.wait("post-compute-wait", Duration.ofSeconds(10));

        return String.format("Completed %d async steps. Sum: %d, Time: %dms", STEP_COUNT, totalSum, executionTimeMs);
    }
}
