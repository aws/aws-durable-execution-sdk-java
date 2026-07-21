// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-18: Child context with step and wait inside, step and wait after */
public class ChildStepWaitAfter extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        context.runInChildContext("step-wait-child", String.class, child -> {
            child.step("inner-work", String.class, stepCtx -> input);
            child.wait(null, Duration.ofSeconds(2));
            return input;
        });

        String result = context.step("outer-work", String.class, stepCtx -> input);
        context.wait(null, Duration.ofSeconds(2));
        return result;
    }
}
