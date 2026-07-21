// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/** 6-1: Wait-for-condition basic (polls until threshold met) */
public class WaitForConditionBasic extends DurableHandler<Integer, Integer> {

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
                WaitForConditionConfig.<Integer>builder().initialState(0).build());
    }
}
