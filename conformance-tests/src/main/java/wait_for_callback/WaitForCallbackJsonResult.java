// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;

/** 7-11: Wait-for-callback with structured (JSON) result deserialization. */
public class WaitForCallbackJsonResult extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        Map<String, String> result =
                context.waitForCallback(input, new TypeToken<Map<String, String>>() {}, (callbackId, stepCtx) -> {
                    // Submitter completes normally.
                });
        return result.get("status");
    }
}
