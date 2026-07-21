// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;

/** 7-12: Wait-for-callback heartbeat timeout (no heartbeat sent). */
public class WaitForCallbackHeartbeatTimeout extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var config = WaitForCallbackConfig.builder()
                .callbackConfig(CallbackConfig.builder()
                        .heartbeatTimeout(Duration.ofSeconds(5))
                        .build())
                .build();
        // Do not catch — let the heartbeat timeout propagate so the execution fails.
        return context.waitForCallback(
                input,
                String.class,
                (callbackId, stepCtx) -> {
                    // Submitter completes normally.
                },
                config);
    }
}
