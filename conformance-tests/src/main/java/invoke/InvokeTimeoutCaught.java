// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.exception.InvokeException;

/** 5-8: Invoke timeout, caught (timeout caught, execution continues) */
public class InvokeTimeoutCaught extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        try {
            context.invoke("invoke-slow", functionName, null, String.class);
        } catch (InvokeException e) {
            // Timeout caught
        }
        return "fallback";
    }
}
