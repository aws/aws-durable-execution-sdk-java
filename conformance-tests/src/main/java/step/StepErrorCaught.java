// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 1-20: Error caught and handled (try/catch) */
public class StepErrorCaught extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        try {
            context.step(
                    "failing-step",
                    String.class,
                    stepCtx -> {
                        throw new RuntimeException("Something went wrong");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        } catch (RuntimeException e) {
            // Error caught, continue with fallback
        }

        return context.step("fallback-step", String.class, stepCtx -> "fallback_result");
    }
}
