// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/** 6-2: Wait-for-condition immediate (condition already met on first check) */
public class WaitForConditionImmediate extends DurableHandler<Integer, Integer> {

    @Override
    public Integer handleRequest(Integer input, DurableContext context) {
        return context.waitForCondition(
                null,
                Integer.class,
                (state, stepCtx) -> {
                    // Convention shared with the Python/JS examples: initial state comes from
                    // the input and the threshold is a fixed constant (5). For input 5 the
                    // condition (state >= 5) is already met on the first check.
                    if (state >= 5) {
                        return WaitForConditionResult.stopPolling(state);
                    }
                    return WaitForConditionResult.continuePolling(state);
                },
                WaitForConditionConfig.<Integer>builder().initialState(input).build());
    }
}
