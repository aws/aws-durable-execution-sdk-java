// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 1-19: Step with error (fails permanently) */
public class StepWithError extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "failing-step",
                String.class,
                stepCtx -> {
                    throw new RuntimeException("Something went wrong");
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                        .build());
    }
}
