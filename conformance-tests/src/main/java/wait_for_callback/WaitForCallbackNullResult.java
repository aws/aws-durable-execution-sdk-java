// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-15: Wait-for-callback success with empty (null) payload. */
public class WaitForCallbackNullResult extends DurableHandler<String, Object> {
    @Override
    public Object handleRequest(String input, DurableContext context) {
        return context.waitForCallback(input, Object.class, (callbackId, stepCtx) -> {
            // Submitter completes normally.
        });
    }
}
