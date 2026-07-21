// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 4-12: Callback success then wait, multi-invocation replay. */
public class CallbackReplayWithWait extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var callback = context.createCallback(input, String.class);
        var cbResult = callback.get();
        context.wait("after-cb", Duration.ofSeconds(2));
        return cbResult;
    }
}
