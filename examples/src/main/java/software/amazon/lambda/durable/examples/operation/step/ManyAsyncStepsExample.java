// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.step;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.ManyAsyncStepsInput;
import software.amazon.lambda.durable.examples.types.ManyAsyncStepsOutput;
import software.amazon.lambda.durable.operation.DurableStepOperation;
import software.amazon.lambda.durable.operation.DurableWaitOperation;

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
public class ManyAsyncStepsExample extends DurableHandler<ManyAsyncStepsInput, ManyAsyncStepsOutput> {

    @Override
    public ManyAsyncStepsOutput handleRequest(ManyAsyncStepsInput input) {
        var startTime = System.nanoTime();
        var multiplier = input.multiplier();
        var steps = input.steps();
        var logger = DurableContext.getCurrentContext().getLogger();

        logger.info("Starting {} async steps with multiplier {}", steps, multiplier);

        // Create async steps
        var futures = new ArrayList<DurableFuture<Integer>>(steps);
        for (var i = 0; i < steps; i++) {
            var index = i;
            var future = DurableStepOperation.stepAsync("compute-" + i, Integer.class, () -> index * multiplier);
            futures.add(future);
        }

        logger.info("All {} async steps created, collecting results", steps);

        // Collect all results using allOf
        var results = DurableFuture.allOf(futures);
        var totalSum = results.stream().mapToInt(Integer::intValue).sum();

        // checkpoint the executionTime so that we can have the same value when replay
        var executionTimeMs = DurableStepOperation.step(
                "execution-time", Long.class, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime));
        logger.info("Completed {} steps, total sum: {}, execution time: {}ms", steps, totalSum, executionTimeMs);

        // Wait 2 seconds to test replay
        DurableWaitOperation.wait("post-compute-wait", Duration.ofSeconds(2));

        var replayTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        return new ManyAsyncStepsOutput(totalSum, executionTimeMs, replayTimeMs);
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
