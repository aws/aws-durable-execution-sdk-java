// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryDecision;

/** 1-15: Retry specific exception (retries only TransientError) */
public class StepRetrySpecificException extends DurableHandler<Object, String> {

    public static class TransientError extends RuntimeException {
        public TransientError(String message) {
            super(message);
        }
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "specific-retry",
                String.class,
                stepCtx -> {
                    // Use the SDK's built-in attempt number (1-based at runtime):
                    // fail on the first attempt, succeed on the second.
                    if (stepCtx.getAttempt() < 2) {
                        throw new TransientError("Temporary failure");
                    }
                    return "recovered from transient";
                },
                StepConfig.builder()
                        .retryStrategy((error, attempt) -> {
                            if (error instanceof TransientError && attempt < 3) {
                                return RetryDecision.retry(Duration.ofSeconds(1));
                            }
                            return RetryDecision.fail();
                        })
                        .build());
    }
}
