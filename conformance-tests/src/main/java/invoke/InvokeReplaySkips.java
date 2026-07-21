// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 5-9: Invoke replay skips (invoke result cached on replay) */
public class InvokeReplaySkips extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        String result = context.invoke("invoke-target", functionName, null, String.class);
        context.wait(null, Duration.ofSeconds(1));
        return result;
    }
}
