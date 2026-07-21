// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.WaitStrategies;

/** 6-5: Wait-for-condition fixed-delay wait strategy */
public class WaitForConditionFixedDelay extends DurableHandler<Integer, Integer> {

    @Override
    public Integer handleRequest(Integer threshold, DurableContext context) {
        return context.waitForCondition(
                null,
                Integer.class,
                (state, stepCtx) -> {
                    int next = state + 1;
                    if (next >= threshold) {
                        return WaitForConditionResult.stopPolling(next);
                    }
                    return WaitForConditionResult.continuePolling(next);
                },
                WaitForConditionConfig.<Integer>builder()
                        .initialState(0)
                        .waitStrategy(WaitStrategies.fixedDelay(60, Duration.ofSeconds(2)))
                        .build());
    }
}
