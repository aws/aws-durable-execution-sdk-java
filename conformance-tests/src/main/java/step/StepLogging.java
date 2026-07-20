// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-7: Step with context logger */
public class StepLogging extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> {
            stepCtx.getLogger().info("Greeting step started for: {}", input);
            String greeting = "Hello, " + input + "!";
            stepCtx.getLogger().info("Greeting step completed with: {}", greeting);
            return greeting;
        });
    }
}
