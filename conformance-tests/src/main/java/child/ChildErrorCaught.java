// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 3-5: Child context error caught (try/catch, execution succeeds) */
public class ChildErrorCaught extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        try {
            context.runInChildContext(
                    "failing-child",
                    String.class,
                    child -> child.step(
                            "failing-step",
                            String.class,
                            stepCtx -> {
                                throw new RuntimeException("Something went wrong");
                            },
                            StepConfig.builder()
                                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                    .build()));
        } catch (RuntimeException e) {
            // Error caught, continue with recovery
        }

        return context.step("recovery-step", String.class, stepCtx -> input);
    }
}
