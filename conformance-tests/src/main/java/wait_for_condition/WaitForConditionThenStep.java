// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/** 6-12: Wait-for-condition followed by a step (result passed onward) */
public class WaitForConditionThenStep extends DurableHandler<Integer, Integer> {

    @Override
    public Integer handleRequest(Integer threshold, DurableContext context) {
        Integer pollResult = context.waitForCondition(
                null,
                Integer.class,
                (state, stepCtx) -> {
                    int next = state + 1;
                    if (next >= threshold) {
                        return WaitForConditionResult.stopPolling(next);
                    }
                    return WaitForConditionResult.continuePolling(next);
                },
                WaitForConditionConfig.<Integer>builder().initialState(0).build());

        return context.step(null, Integer.class, stepCtx -> pollResult * 10);
    }
}
