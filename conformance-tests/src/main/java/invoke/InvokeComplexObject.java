// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-3: Invoke returning complex object (nested JSON) */
public class InvokeComplexObject extends DurableHandler<Object, Object> {

    @Override
    public Object handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        return context.invoke("invoke-complex", functionName, input, Object.class);
    }
}
