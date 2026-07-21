// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-3: Wait-for-callback with anonymous submitter (no name). */
public class WaitForCallbackAnonymous extends DurableHandler<Object, String> {
    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.waitForCallback(null, String.class, (callbackId, stepCtx) -> {
            // Anonymous submitter — no name passed.
        });
    }
}
