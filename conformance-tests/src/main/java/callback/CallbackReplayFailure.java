// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.exception.CallbackException;

/** 4-13: Callback failure caught, then wait. */
public class CallbackReplayFailure extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var callback = context.createCallback(input, String.class);

        String outcome;
        try {
            outcome = callback.get();
        } catch (CallbackException e) {
            outcome = "caught_failure:" + e.getMessage();
        } catch (RuntimeException e) {
            outcome = "caught_other:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }

        context.wait("after-cb", Duration.ofSeconds(2));
        return outcome;
    }
}
