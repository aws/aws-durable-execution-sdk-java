// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-12: Invoke then step (invoke result used by subsequent step) */
public class InvokeThenStep extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        String invokeResult = context.invoke("invoke-target", functionName, null, String.class);
        return context.step("process", String.class, stepCtx -> "processed: " + invokeResult);
    }
}
