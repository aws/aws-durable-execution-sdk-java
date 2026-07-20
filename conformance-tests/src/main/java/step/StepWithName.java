// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-2: Step with name */
public class StepWithName extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.step("custom_step_name", String.class, stepCtx -> "Hello, " + input + "!");
    }
}
