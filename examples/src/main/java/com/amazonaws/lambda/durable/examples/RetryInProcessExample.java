// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableFuture;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.retry.JitterStrategy;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating in-process retry behavior with concurrent operations.
 *
 * <p>This example shows:
 *
 * <ul>
 *   <li>An async step that fails and retries while other work continues
 *   <li>A long-running synchronous step that keeps the process busy
 *   <li>Retry happens in-process without suspension because main thread is active
 * </ul>
 */
public class RetryInProcessExample extends DurableHandler<Object, String> {

    private static final Logger logger = LoggerFactory.getLogger(RetryInProcessExample.class);

    private final AtomicInteger attemptCount = new AtomicInteger(0);

    @Override
    protected String handleRequest(Object input, DurableContext context) {
        logger.info("Starting retry in-process example");

        // Start async step that will fail and retry
        DurableFuture<String> asyncStep = context.stepAsync(
                "flaky-async-operation",
                String.class,
                () -> {
                    int attempt = attemptCount.incrementAndGet();
                    logger.info(
                            "Async operation attempt #{} in thread: {}",
                            attempt,
                            Thread.currentThread().getName());

                    // Fail first 2 attempts, succeed on 3rd
                    if (attempt < 3) {
                        var message = "Async operation failing on attempt " + attempt;
                        logger.warn(message);
                        throw new RuntimeException(message);
                    } else {
                        var message = "Async operation succeeded on attempt " + attempt;
                        logger.info(message);
                        return message;
                    }
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.exponentialBackoff(
                                5, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, JitterStrategy.NONE))
                        .build());

        // Long-running synchronous step that keeps process busy
        // This prevents suspension during async step retries
        String syncResult = context.step("long-running-operation", String.class, () -> {
            logger.info(
                    "Starting long-running operation (10 seconds) in thread: {}",
                    Thread.currentThread().getName());
            try {
                Thread.sleep(10000); // 10 seconds
                logger.info("Long-running operation completed");
                return "Long operation completed";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Long operation interrupted", e);
            }
        });

        // Get async step result (should be ready by now due to retries during sync
        // step)
        logger.info("Getting async step result");
        String asyncResult = asyncStep.get();

        logger.info("Sync result: {}", syncResult);
        logger.info("Async result: {}", asyncResult);

        return "Retry in-process completed - Sync: " + syncResult + ", Async: " + asyncResult;
    }
}
