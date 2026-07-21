// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 1-12: Retry exhaustion (max attempts) */
public class StepRetryExhaustion extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "always-fails",
                String.class,
                stepCtx -> {
                    throw new RuntimeException("Always fails");
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.exponentialBackoff(
                                4, Duration.ofSeconds(1), Duration.ofSeconds(10), 1.0, JitterStrategy.NONE))
                        .build());
    }
}
