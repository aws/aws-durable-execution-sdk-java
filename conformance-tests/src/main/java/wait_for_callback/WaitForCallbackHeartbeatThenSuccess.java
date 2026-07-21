// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;

/** 7-13: Wait-for-callback with heartbeat then success. */
public class WaitForCallbackHeartbeatThenSuccess extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var config = WaitForCallbackConfig.builder()
                .callbackConfig(CallbackConfig.builder()
                        .heartbeatTimeout(Duration.ofSeconds(10))
                        .build())
                .build();
        return context.waitForCallback(
                input,
                String.class,
                (callbackId, stepCtx) -> {
                    // Submitter completes normally.
                },
                config);
    }
}
