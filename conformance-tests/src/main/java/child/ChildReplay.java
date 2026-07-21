// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-9: Child context replay (returns cached result) */
public class ChildReplay extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        String childResult = context.runInChildContext(
                "cached-child", String.class, child -> child.step("compute", String.class, stepCtx -> input));

        context.wait(null, Duration.ofSeconds(2));

        return childResult;
    }
}
