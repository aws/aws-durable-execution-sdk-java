// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.retry.RetryDecision;

/** 1-18: AtMostOnce interrupted (with retry, succeeds on second attempt) */
public class StepAtMostOnceWithRetry extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "at-most-once-retry",
                String.class,
                stepCtx -> {
                    // Print input to stdout each time step executes
                    System.out.println(input);
                    System.out.flush();

                    // Use the SDK's built-in attempt number (1-based at runtime).
                    // With AT_MOST_ONCE_PER_RETRY the interrupted first attempt is
                    // checkpointed as a retry, so the re-executed step sees a higher
                    // attempt number and succeeds.
                    if (stepCtx.getAttempt() < 2) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        System.exit(1);
                    }
                    return "succeeded on second attempt";
                },
                StepConfig.builder()
                        .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                        .retryStrategy((error, attempt) -> {
                            if (attempt >= 3) return RetryDecision.fail();
                            return RetryDecision.retry(Duration.ofSeconds(1));
                        })
                        .build());
    }
}
