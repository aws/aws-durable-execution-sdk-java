// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-11: Step then invoke (sequential operations) */
public class StepThenInvoke extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        String stepResult = context.step("compute", String.class, stepCtx -> "step-data");
        return context.invoke("invoke-with-step", functionName, stepResult, String.class);
    }
}
