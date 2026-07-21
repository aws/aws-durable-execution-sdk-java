// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryDecision;

/** 1-16: Retry specific exception (non-retryable fails) */
public class StepRetryNonRetryable extends DurableHandler<Object, String> {

    public static class TransientError extends RuntimeException {
        public TransientError(String message) {
            super(message);
        }
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "non-retryable",
                String.class,
                stepCtx -> {
                    throw new TransientError("Temporary failure");
                },
                StepConfig.builder()
                        .retryStrategy((error, attempt) -> {
                            // Only retry ValidationError, not TransientError
                            if (error.getClass().getSimpleName().equals("ValidationError") && attempt < 3) {
                                return RetryDecision.retry(Duration.ofSeconds(1));
                            }
                            return RetryDecision.fail();
                        })
                        .build());
    }
}
