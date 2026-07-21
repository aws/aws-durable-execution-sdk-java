// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.exception.CallbackException;

/** 4-14: Callback timeout caught, then wait. */
public class CallbackReplayTimeout extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var config = CallbackConfig.builder().timeout(Duration.ofSeconds(3)).build();
        var callback = context.createCallback(input, String.class, config);

        String outcome;
        try {
            outcome = callback.get();
        } catch (CallbackException e) {
            outcome = "caught_timeout:" + e.getMessage();
        } catch (RuntimeException e) {
            outcome = "caught_other:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }

        context.wait("after-cb", Duration.ofSeconds(2));
        return outcome;
    }
}
