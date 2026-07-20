// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-9: Replay skips succeeded step */
public class StepReplaySkipsSucceeded extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String result = context.step("cached-step", String.class, stepCtx -> {
            stepCtx.getLogger().info("step executed");
            return "cached_value";
        });
        context.wait(null, Duration.ofSeconds(1));
        return result;
    }
}
