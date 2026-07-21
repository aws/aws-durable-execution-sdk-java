// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.WaitStrategies;

/** 6-6: Wait-for-condition max attempts exceeded (failure) */
public class WaitForConditionMaxAttempts extends DurableHandler<Object, Integer> {

    @Override
    public Integer handleRequest(Object input, DurableContext context) {
        return context.waitForCondition(
                null,
                Integer.class,
                (state, stepCtx) -> {
                    // Condition is never met; always continue polling
                    return WaitForConditionResult.continuePolling(state + 1);
                },
                WaitForConditionConfig.<Integer>builder()
                        .initialState(0)
                        .waitStrategy(WaitStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                        .build());
    }
}
