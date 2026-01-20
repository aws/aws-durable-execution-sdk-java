// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Example demonstrating nested step calling with stepAsync.
 *
 * <p>This example shows how to:
 *
 * <ol>
 *   <li>Create an async step that performs long-running work
 *   <li>Use the result of that async step within another step
 *   <li>Properly coordinate execution between multiple steps
 * </ol>
 */
public class NestedStepExample extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        // Step 1: Create an async step that performs long-running work
        var durableFuture1 = context.stepAsync(
                "async-step",
                Integer.class,
                () -> {
                    var sleepSeconds = ThreadLocalRandom.current().nextInt(5, 11);
                    try {
                        Thread.sleep(sleepSeconds * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted", e);
                    }
                    return sleepSeconds;
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build());

        // Step 2: Process the result of the async step
        return context.step("process-result", String.class, () -> {
            var sleptSeconds = durableFuture1.get();
            return "slept-" + sleptSeconds + "-seconds";
        });
    }
}
