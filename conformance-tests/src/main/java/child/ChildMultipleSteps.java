// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-3: Child context with multiple sequential steps */
public class ChildMultipleSteps extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.runInChildContext("multi-step", String.class, child -> {
            String first = child.step("step-one", String.class, stepCtx -> input);
            String second = child.step("step-two", String.class, stepCtx -> first);
            return second;
        });
    }
}
