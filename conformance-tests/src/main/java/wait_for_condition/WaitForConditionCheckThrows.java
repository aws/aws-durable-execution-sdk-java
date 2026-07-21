// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 6-7: Wait-for-condition check function throws (uncaught failure) */
public class WaitForConditionCheckThrows extends DurableHandler<Object, Object> {

    @Override
    public Object handleRequest(Object input, DurableContext context) {
        return context.waitForCondition(null, Object.class, (state, stepCtx) -> {
            throw new RuntimeException("Check function error");
        });
    }
}
