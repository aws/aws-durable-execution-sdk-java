// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** 1-10: Replay re-throws failed step */
public class StepReplayRethrowsFailed extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String errorMessage = "";

        try {
            context.step(
                    "failing-step",
                    String.class,
                    stepCtx -> {
                        stepCtx.getLogger().info("step executed");
                        throw new RuntimeException("Something went wrong");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        } catch (RuntimeException e) {
            errorMessage = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        }

        context.wait(null, Duration.ofSeconds(1));
        return "caught: " + errorMessage;
    }
}
