// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.StepConfig;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple example demonstrating retry strategies with a flaky API.
 *
 * <p>This example shows:
 *
 * <ul>
 *   <li>A step that never retries (fails immediately)
 *   <li>A step that retries with default exponential backoff
 *   <li>Time-based failure simulation for realistic retry behavior
 * </ul>
 */
public class RetryExample extends DurableHandler<Object, String> {

    private static final Logger logger = LoggerFactory.getLogger(RetryExample.class);

    private Instant startTime;

    @Override
    public String handleRequest(Object input, DurableContext context) {
        // Step 1: Record start time
        startTime = context.step("record-start-time", Instant.class, Instant::now);
        logger.info("Recorded start time: {}", startTime);

        // Step 2: Call that never retries (fails immediately)
        try {
            context.step(
                    "no-retry-call",
                    Void.class,
                    () -> {
                        throw new RuntimeException("This operation never retries");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        } catch (Exception e) {
            logger.info("No-retry step failed as expected: {}", e.getMessage());
        }

        // Step 3: Flaky API call that succeeds after retries
        var result = context.step(
                "flaky-api-call",
                String.class,
                () -> {
                    // Fail for first 8 seconds, then succeed
                    var failForMillis = 8000;
                    var elapsed = Duration.between(startTime, Instant.now());

                    if (elapsed.toMillis() < failForMillis) {
                        var message = String.format(
                                "Flaky API failing - elapsed time (%.1fs) < %.1fs",
                                elapsed.toMillis() / 1000.0, failForMillis / 1000.0);
                        logger.warn(message);
                        throw new RuntimeException(message);
                    } else {
                        var message = String.format(
                                "Flaky API succeeded - elapsed time (%.1fs) >= %.1fs",
                                elapsed.toMillis() / 1000.0, failForMillis / 1000.0);
                        logger.info(message);
                        return message;
                    }
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build());

        logger.info("Flaky API result: {}", result);

        // Step 4: Wait a bit before finishing
        context.wait(Duration.ofSeconds(2));

        logger.info("Retry example completed successfully");
        return "Retry example completed: " + result;
    }
}
