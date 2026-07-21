// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-6: Nested child contexts */
public class ChildNested extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.runInChildContext("outer", String.class, outerChild -> {
            outerChild.step("outer-step", String.class, stepCtx -> input);

            String innerResult = outerChild.runInChildContext(
                    "inner", String.class, innerChild -> innerChild.step("inner-step", String.class, stepCtx -> input));

            return innerResult;
        });
    }
}
