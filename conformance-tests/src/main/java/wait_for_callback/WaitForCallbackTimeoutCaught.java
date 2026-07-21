// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.execution.SuspendExecutionException;

/** 7-14: Wait-for-callback timeout caught (recovers). */
public class WaitForCallbackTimeoutCaught extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var config = WaitForCallbackConfig.builder()
                .callbackConfig(
                        CallbackConfig.builder().timeout(Duration.ofSeconds(3)).build())
                .build();
        try {
            return context.waitForCallback(
                    input,
                    String.class,
                    (callbackId, stepCtx) -> {
                        // Submitter completes normally.
                    },
                    config);
        } catch (SuspendExecutionException e) {
            throw e;
        } catch (Exception e) {
            return "timed-out-handled";
        }
    }
}
