// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-14: Multiple sequential invokes */
public class InvokeMultipleSequential extends DurableHandler<Map<String, Object>, String> {

    @Override
    @SuppressWarnings("unchecked")
    public String handleRequest(Map<String, Object> input, DurableContext context) {
        String functionName1 = System.getenv("TARGET_FUNCTION_NAME_1");
        String functionName2 = System.getenv("TARGET_FUNCTION_NAME_2");
        String result1 = context.invoke("invoke-first", functionName1, null, String.class);
        String result2 = context.invoke("invoke-second", functionName2, null, String.class);
        return result1 + "," + result2;
    }
}
