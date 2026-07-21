// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.execution.SuspendExecutionException;

/** 7-6: Wait-for-callback external failure caught (recovers). */
public class WaitForCallbackFailureCaught extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        try {
            return context.waitForCallback(input, String.class, (callbackId, stepCtx) -> {
                // Submitter completes normally.
            });
        } catch (SuspendExecutionException e) {
            throw e;
        } catch (Exception e) {
            return "recovered";
        }
    }
}
