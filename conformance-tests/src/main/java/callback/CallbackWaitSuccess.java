// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 4-9: Callback + Wait + success. */
public class CallbackWaitSuccess extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var callback = context.createCallback(input, String.class);
        context.wait("delay", Duration.ofSeconds(5));
        return callback.get();
    }
}
