// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 4-7: Callback + Step + failure. */
public class CallbackStepFailure extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var callback = context.createCallback(input, String.class);
        context.step("notify-external", String.class, ctx -> "notified");
        return callback.get();
    }
}
