// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-10: Wait-for-callback mixed with wait and step. */
public class WaitForCallbackMixedOps extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        // 1. Durable wait (1 second)
        context.wait(null, Duration.ofSeconds(1));

        // 2. Top-level step returning fixed data
        context.step(null, String.class, stepCtx -> "fixed-data");

        // 3. Wait-for-callback using input as operation name
        return context.waitForCallback(input, String.class, (callbackId, stepCtx) -> {
            // Submitter completes normally.
        });
    }
}
