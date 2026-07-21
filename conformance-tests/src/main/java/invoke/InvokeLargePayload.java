// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-7: Invoke large payload (payload near size limit) */
public class InvokeLargePayload extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        // Build a large payload string (~200KB)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200000; i++) {
            sb.append("x");
        }
        String largePayload = sb.toString();
        return context.invoke("invoke-large", functionName, largePayload, String.class);
    }
}
