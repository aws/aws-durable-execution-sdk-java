// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 3-8: Child context with step retry exhaustion (child fails) */
public class ChildRetryExhaustion extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.runInChildContext(
                "exhaust-child",
                String.class,
                child -> child.step(
                        "always-fails",
                        String.class,
                        stepCtx -> {
                            throw new RuntimeException("Always fails");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.exponentialBackoff(
                                        2, Duration.ofSeconds(1), Duration.ofSeconds(10), 1.0, JitterStrategy.NONE))
                                .build()));
    }
}
