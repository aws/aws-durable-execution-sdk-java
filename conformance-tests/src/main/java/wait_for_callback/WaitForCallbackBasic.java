// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-1: Wait-for-callback basic (success via external callback). */
public class WaitForCallbackBasic extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.waitForCallback(input, String.class, (callbackId, stepCtx) -> {
            // Submitter receives the callback id; nothing durable to do.
        });
    }
}
