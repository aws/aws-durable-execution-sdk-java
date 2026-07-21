// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.exception.InvokeException;

/** 5-10: Invoke replay re-throws (failed invoke error re-thrown from cache) */
public class InvokeReplayRethrows extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        try {
            context.invoke("invoke-failing", functionName, null, String.class);
        } catch (InvokeException e) {
            // Caught on first execution and on replay
        }
        context.wait(null, Duration.ofSeconds(1));
        return "done";
    }
}
