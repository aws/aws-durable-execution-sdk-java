// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 7-7: Wait-for-callback submitter retry exhaustion. */
public class WaitForCallbackSubmitterRetryExhaustion extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var config = WaitForCallbackConfig.builder()
                .stepConfig(StepConfig.builder()
                        .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                        .build())
                .build();
        // Submitter always throws; do not catch — let the execution fail.
        return context.waitForCallback(
                input,
                String.class,
                (callbackId, stepCtx) -> {
                    throw new RuntimeException("submitter always fails");
                },
                config);
    }
}
