// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/** 6-10: Wait-for-condition null result */
public class WaitForConditionNullResult extends DurableHandler<Object, Object> {

    @Override
    public Object handleRequest(Object input, DurableContext context) {
        return context.waitForCondition(
                null, Object.class, (state, stepCtx) -> WaitForConditionResult.stopPolling(null));
    }
}
