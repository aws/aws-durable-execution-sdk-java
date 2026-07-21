// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/** 6-13: Multiple sequential wait_for_condition operations — first result seeds second */
public class WaitForConditionMultipleSequential extends DurableHandler<Object, Integer> {

    @Override
    public Integer handleRequest(Object input, DurableContext context) {
        // First wait_for_condition: initial state 0, threshold 2
        Integer firstResult = context.waitForCondition(
                null,
                Integer.class,
                (state, stepCtx) -> {
                    int next = state + 1;
                    if (next >= 2) {
                        return WaitForConditionResult.stopPolling(next);
                    }
                    return WaitForConditionResult.continuePolling(next);
                },
                WaitForConditionConfig.<Integer>builder().initialState(0).build());

        // Second wait_for_condition: initial state = firstResult (2), threshold 4
        Integer secondResult = context.waitForCondition(
                null,
                Integer.class,
                (state, stepCtx) -> {
                    int next = state + 1;
                    if (next >= 4) {
                        return WaitForConditionResult.stopPolling(next);
                    }
                    return WaitForConditionResult.continuePolling(next);
                },
                WaitForConditionConfig.<Integer>builder()
                        .initialState(firstResult)
                        .build());

        return secondResult;
    }
}
