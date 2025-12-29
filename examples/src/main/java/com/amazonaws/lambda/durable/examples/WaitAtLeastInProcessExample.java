// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableFuture;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating concurrent stepAsync() with wait() operations where no suspension occurs.
 *
 * <p>This example shows in-process wait behavior:
 *
 * <ul>
 *   <li>stepAsync() starts a background operation (takes 10 seconds)
 *   <li>wait() is called immediately (3 second duration)
 *   <li>The async step takes longer than the wait duration
 *   <li>No suspension occurs because we've already waited long enough
 * </ul>
 */
public class WaitAtLeastInProcessExample extends DurableHandler<GreetingRequest, String> {

    private static final Logger logger = LoggerFactory.getLogger(WaitAtLeastInProcessExample.class);

    @Override
    protected String handleRequest(GreetingRequest input, DurableContext context) {
        logger.info("Starting concurrent step + wait example for: {}", input.getName());

        // Start an async step that takes 10 seconds
        DurableFuture<String> asyncStep = context.stepAsync(
                "async-operation",
                String.class,
                () -> {
                    logger.info(
                            "Async operation starting in thread: {}",
                            Thread.currentThread().getName());
                    try {
                        Thread.sleep(10000); // 10 seconds
                        logger.info("Async operation completed successfully");
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
        // The async step will still be running and will complete after the wait
        logger.info("Waiting 3 seconds (async step will complete in 10s - no suspension expected)");
        context.wait("wait-3-seconds", Duration.ofSeconds(3));

        // After wait, get the async step result
        logger.info("Wait completed, getting async result");
        String result = asyncStep.get();
        logger.info("Final result: {}", result);

        return result;
    }
}
