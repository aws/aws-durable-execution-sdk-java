// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 4-10: Callback + Wait + failure. */
public class CallbackWaitFailure extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var callback = context.createCallback(input, String.class);
        context.wait("delay", Duration.ofSeconds(5));
        return callback.get();
    }
}
