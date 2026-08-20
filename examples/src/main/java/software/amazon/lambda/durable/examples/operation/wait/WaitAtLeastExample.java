// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.wait;

import static software.amazon.lambda.durable.logging.DurableLogger.getLogger;
import static software.amazon.lambda.durable.operation.DurableStepOperation.stepAsync;

import java.time.Duration;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.operation.DurableStepOperation.StepConfig;
import software.amazon.lambda.durable.operation.DurableWaitOperation;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Example demonstrating concurrent stepAsync() with DurableWaitOperation.wait() operations.
 *
 * <p>This example shows suspension behavior with pending async steps:
 *
 * <ul>
 *   <li>stepAsync() starts a background operation (takes 2 seconds)
 *   <li>DurableWaitOperation.wait() is called immediately (3 second duration)
 *   <li>The step completes successfully before suspension
 *   <li>Execution suspends for the wait time
 * </ul>
 */
public class WaitAtLeastExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input) {
        getLogger().info("Starting concurrent step + wait example for: {}", input.getName());

        // Start an async step that takes 2 seconds
        DurableFuture<String> asyncStep = stepAsync(
                "async-operation",
                String.class,
                () -> {
                    getLogger()
                            .info(
                                    "Async operation starting in thread: {}",
                                    Thread.currentThread().getName());
                    try {
                        Thread.sleep(2000); // 2 seconds
                        getLogger().info("Async operation completed successfully");
                        return "Processed: " + input.getName();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Operation interrupted", e);
                    }
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build());

        // Immediately wait for 3 seconds
        // The async step will complete during this wait
        getLogger().info("Waiting 3 seconds (async step will complete in 2s)");
        DurableWaitOperation.wait("wait-3-seconds", Duration.ofSeconds(3));

        // After wait, get the async step result
        getLogger().info("Resumed after wait");
        String result = asyncStep.get();
        getLogger().info("Final result: {}", result);

        return result;
    }
}
