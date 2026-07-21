// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-13: Default retry strategy (no explicit config) */
public class StepDefaultRetry extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step("default-retry", String.class, stepCtx -> {
            // Use the SDK's built-in attempt number (1-based at runtime):
            // fail on the first two attempts, succeed on the third.
            if (stepCtx.getAttempt() < 3) {
                throw new RuntimeException("Attempt " + stepCtx.getAttempt() + " failed");
            }
            return "recovered";
        });
    }
}
