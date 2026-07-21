// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-2: Wait-for-callback with explicit static name ("approval"). */
public class WaitForCallbackExplicitName extends DurableHandler<Object, String> {
    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.waitForCallback("approval", String.class, (callbackId, stepCtx) -> {
            // Submitter completes without side effects.
        });
    }
}
