// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-13: Invoke inside child context */
public class InvokeInChildContext extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        return context.runInChildContext(
                "child-invoke",
                String.class,
                child -> child.invoke("invoke-in-child", functionName, null, String.class));
    }
}
