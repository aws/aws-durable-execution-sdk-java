// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 7-9: Multiple sequential wait-for-callback operations. */
public class WaitForCallbackTwoSequential extends DurableHandler<Object, String> {
    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.waitForCallback("first", String.class, (callbackId, stepCtx) -> {
            // First submitter completes.
        });

        String second = context.waitForCallback("second", String.class, (callbackId, stepCtx) -> {
            // Second submitter completes.
        });

        return second;
    }
}
