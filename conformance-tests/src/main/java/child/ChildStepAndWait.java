// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-10: Child context with step and wait inside */
public class ChildStepAndWait extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.runInChildContext("mixed-ops", String.class, child -> {
            child.step("do-work", String.class, stepCtx -> input);
            child.wait(null, Duration.ofSeconds(2));
            return input;
        });
    }
}
