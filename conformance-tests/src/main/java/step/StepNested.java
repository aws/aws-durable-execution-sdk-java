// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-3: Sequential steps where second depends on first */
public class StepNested extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String result1 = context.step("step-one", String.class, stepCtx -> "first");
        String result2 = context.step("step-two", String.class, stepCtx -> result1 + "_second");
        return result2;
    }
}
