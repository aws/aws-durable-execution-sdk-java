// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.InvokeConfig;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.PollingStrategies;

/**
 * Example demonstrating custom polling strategy configuration.
 *
 * <p>The polling strategy controls how the SDK polls for async operation results. By default, the SDK uses exponential
 * backoff (1s base, 2x rate, full jitter). This example shows how to customize the polling behavior.
 *
 * <p>This example configures:
 *
 * <ul>
 *   <li>Exponential backoff with 500ms base interval
 *   <li>1.5x backoff rate for gentler growth
 *   <li>Half jitter to balance between consistency and thundering herd avoidance
 * </ul>
 */
public class CustomPollingExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPollingStrategy(PollingStrategies.exponentialBackoff(
                        Duration.ofMillis(500), 1.5, JitterStrategy.HALF, Duration.ofSeconds(5)))
                .build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting workflow with input: {}", input);

        // Step 1: low case the input
        var lowered = context.stepAsync("validate", String.class, () -> {
            try {
                // prevent the execution from suspension
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return input.getName().toLowerCase();
        });

        // Step 2: Invoke async
        var future = context.invokeAsync(
                "call-greeting",
                "simple-step-example" + input.getName() + ":$LATEST",
                input,
                String.class,
                InvokeConfig.builder().build());
        // because we are sleeping 5 seconds in the first async step, the function will not be suspened. The invoke
        // function will have to poll for completion.
        return future.get() + lowered.get();
    }
}
