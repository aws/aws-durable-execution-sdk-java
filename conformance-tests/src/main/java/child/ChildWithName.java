// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-2: Child context with name */
public class ChildWithName extends DurableHandler<Map<String, String>, String> {

    @Override
    public String handleRequest(Map<String, String> input, DurableContext context) {
        String name = input.get("name");
        String value = input.get("value");
        return context.runInChildContext(
                name, String.class, child -> child.step("step", String.class, stepCtx -> value));
    }
}
