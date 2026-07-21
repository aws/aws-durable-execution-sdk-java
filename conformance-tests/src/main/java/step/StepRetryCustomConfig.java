// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 1-14: Retry with custom config (2s initial, 3x backoff, no jitter) */
public class StepRetryCustomConfig extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "custom-retry",
                String.class,
                stepCtx -> {
                    // Use the SDK's built-in attempt number (1-based at runtime):
                    // fail on the first two attempts, succeed on the third.
                    if (stepCtx.getAttempt() < 3) {
                        throw new RuntimeException("Attempt " + stepCtx.getAttempt() + " failed");
                    }
                    return "finally succeeded";
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.exponentialBackoff(
                                5, Duration.ofSeconds(2), Duration.ofSeconds(60), 3.0, JitterStrategy.NONE))
                        .build());
    }
}
