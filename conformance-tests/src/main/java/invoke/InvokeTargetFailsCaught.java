// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.exception.InvokeException;

/** 5-6: Invoke target fails, caught (try/catch, execution succeeds) */
public class InvokeTargetFailsCaught extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        try {
            context.invoke("invoke-failing", functionName, null, String.class);
        } catch (InvokeException e) {
            // Error caught
        }
        return "fallback";
    }
}
