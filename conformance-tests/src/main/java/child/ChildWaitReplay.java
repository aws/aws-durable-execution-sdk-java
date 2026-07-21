// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-13: Child context with wait inside - verify replay */
public class ChildWaitReplay extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        context.runInChildContext("wait-child", String.class, child -> {
            child.wait(null, Duration.ofSeconds(1));
            return input;
        });

        return context.step("after-child", String.class, stepCtx -> input);
    }
}
