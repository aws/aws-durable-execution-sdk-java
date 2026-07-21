// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-4: Wait-for-callback external failure (uncaught). */
public class WaitForCallbackExternalFailure extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        // Do not catch — let the external failure propagate so the execution fails.
        return context.waitForCallback(input, String.class, (callbackId, stepCtx) -> {
            // Submitter completes normally.
        });
    }
}
