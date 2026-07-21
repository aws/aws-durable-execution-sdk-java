// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 6-8: Wait-for-condition check throws, caught by handler (recovers) */
public class WaitForConditionCheckThrowsCaught extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        try {
            context.waitForCondition(null, Object.class, (state, stepCtx) -> {
                throw new RuntimeException("Check function error");
            });
        } catch (RuntimeException e) {
            return "recovered";
        }
        // Distinct fallback: if waitForCondition ever stops propagating the check-function
        // error, this path is hit and the test fails (matching Python None / JS undefined),
        // so a lost-exception regression is detectable rather than silently passing.
        return "should_not_reach_here";
    }
}
