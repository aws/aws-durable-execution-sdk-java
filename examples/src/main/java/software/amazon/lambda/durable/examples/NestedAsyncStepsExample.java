// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import java.util.ArrayList;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * An example demonstrating nested async steps.
 *
 * <p>This example calculates the Fibonacci sequence using nested async steps.
 *
 * <ul>
 *   <li>Accepts nth as input
 *   <li>Creates async steps in a loop to calculate Fibonacci numbers
 *   <li>Each step performs a simple computation based on the result from previous two steps
 *   <li>nth Fibonacci number is returned as result
 * </ul>
 */
public class NestedAsyncStepsExample extends DurableHandler<NestedAsyncStepsExample.Input, String> {

    public record Input(int nth) {}

    @Override
    public String handleRequest(Input input, DurableContext context) {
        var startTime = System.currentTimeMillis();
        var steps = input.nth();

        context.getLogger().info("Starting {} async steps", steps);

        // Create async steps
        var futures = new ArrayList<DurableFuture<Long>>(steps);
        for (var i = 0; i < steps; i++) {
            var index = i;
            var future = context.stepAsync(
                    "compute-" + i,
                    Long.class,
                    stepContext -> index < 2
                            ? 1
                            : Math.addExact(
                                    futures.get(index - 1).get(),
                                    futures.get(index - 2).get()),
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
            futures.add(future);
        }

        context.getLogger().info("All {} async steps created, collecting results", steps);

        // Collect all results using allOf
        var last = futures.get(steps - 1).get();

        var executionTimeMs = System.currentTimeMillis() - startTime;
        context.getLogger().info("Completed {} steps, result: {}, execution time: {}ms", steps, last, executionTimeMs);

        // Wait 10 seconds to test replay
        context.wait("post-compute-wait", Duration.ofSeconds(10));

        return String.format("Completed %d async steps. result: %d, Time: %dms", steps, last, executionTimeMs);
    }
}
