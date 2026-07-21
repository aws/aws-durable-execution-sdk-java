// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 1-17: AtMostOnce interrupted (no retry) */
public class StepAtMostOnceNoRetry extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "at_most_once_flaky_step",
                String.class,
                stepCtx -> {
                    System.out.println(input);
                    System.out.flush();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                    System.exit(1);
                    return "unreachable";
                },
                StepConfig.builder()
                        .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }
}
