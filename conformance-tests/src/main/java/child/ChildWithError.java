// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 3-4: Child context error (step fails, execution fails) */
public class ChildWithError extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.runInChildContext(
                "failing-child",
                String.class,
                child -> child.step(
                        "failing-step",
                        String.class,
                        stepCtx -> {
                            throw new RuntimeException("Something went wrong in child");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()));
    }
}
