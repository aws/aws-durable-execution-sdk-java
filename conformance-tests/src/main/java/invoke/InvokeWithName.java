// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-2: Invoke with name (explicit name parameter from input) */
public class InvokeWithName extends DurableHandler<Map<String, Object>, Object> {

    @Override
    public Object handleRequest(Map<String, Object> input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        String name = (String) input.get("name");
        Object payload = input.get("payload");
        return context.invoke(name, functionName, payload, Object.class);
    }
}
