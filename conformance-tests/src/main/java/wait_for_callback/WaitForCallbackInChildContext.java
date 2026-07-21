// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-8: Wait-for-callback inside a child context. */
public class WaitForCallbackInChildContext extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.runInChildContext(
                "wrapper",
                String.class,
                child -> child.waitForCallback(input, String.class, (callbackId, stepCtx) -> {
                    // Submitter completes normally inside the child context.
                }));
    }
}
