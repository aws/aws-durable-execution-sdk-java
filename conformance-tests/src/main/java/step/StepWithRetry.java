// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryDecision;

/** 1-11: Step with retry (fails then succeeds) */
public class StepWithRetry extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "retry-step",
                String.class,
                stepCtx -> {
                    // Use the SDK's built-in attempt number (1-based at runtime):
                    // fail on the first attempt, succeed on the second.
                    if (stepCtx.getAttempt() < 2) {
                        throw new RuntimeException("Attempt " + stepCtx.getAttempt() + " failed");
                    }
                    return "Operation succeeded";
                },
                StepConfig.builder()
                        .retryStrategy((error, attempt) -> {
                            if (attempt >= 3) return RetryDecision.fail();
                            return RetryDecision.retry(Duration.ofSeconds(1));
                        })
                        .build());
    }
}
