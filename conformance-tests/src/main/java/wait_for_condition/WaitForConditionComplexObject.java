// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/** 6-9: Wait-for-condition with a complex object state */
@SuppressWarnings("unchecked")
public class WaitForConditionComplexObject extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        Map<String, Object> initialState = Map.of("status", "PENDING", "attempts", 0);

        return context.waitForCondition(
                null,
                new TypeToken<Map<String, Object>>() {},
                (state, stepCtx) -> {
                    int attempts = ((Number) state.get("attempts")).intValue() + 1;
                    if (attempts >= 2) {
                        Map<String, Object> doneState = Map.of("status", "DONE", "attempts", attempts);
                        return WaitForConditionResult.stopPolling(doneState);
                    }
                    Map<String, Object> nextState = Map.of("status", "PENDING", "attempts", attempts);
                    return WaitForConditionResult.continuePolling(nextState);
                },
                WaitForConditionConfig.<Map<String, Object>>builder()
                        .initialState(initialState)
                        .build());
    }
}
