// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.WaitForConditionConfig;
import software.amazon.lambda.durable.WaitStrategies;
import software.amazon.lambda.durable.retry.JitterStrategy;

/**
 * Example demonstrating the waitForCondition operation.
 *
 * <p>Polls a counter until it reaches the length of the input name, then returns the final count.
 */
public class WaitForConditionExample extends DurableHandler<GreetingRequest, Integer> {

    @Override
    public Integer handleRequest(GreetingRequest input, DurableContext context) {
        var targetCount = input.getName().length();

        var strategy = WaitStrategies.<Integer>builder(state -> state < targetCount)
                .initialDelay(Duration.ofSeconds(1))
                .jitter(JitterStrategy.NONE)
                .build();

        var config = WaitForConditionConfig.<Integer>builder(strategy, 0).build();

        return context.waitForCondition("count-to-name-length", Integer.class, (state, stepCtx) -> state + 1, config);
    }
}
