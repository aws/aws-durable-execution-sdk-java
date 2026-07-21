// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-5: Undefined/null result */
public class StepNullResult extends DurableHandler<Object, Object> {

    @Override
    public Object handleRequest(Object input, DurableContext context) {
        return context.step("do-nothing", Object.class, stepCtx -> null);
    }
}
