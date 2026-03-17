// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.WaitForConditionResult;

/**
 * Example demonstrating the waitForCondition operation.
 *
 * <p>Polls a counter until it reaches the length of the input name, then returns the final count. Uses the minimal API
 * with default configuration — no explicit strategy or config needed.
 */
public class WaitForConditionExample extends DurableHandler<GreetingRequest, Integer> {

    @Override
    public Integer handleRequest(GreetingRequest input, DurableContext context) {
        var targetCount = input.getName().length();

        return context.waitForCondition(
                "count-to-name-length",
                Integer.class,
                (state, stepCtx) -> {
                    var next = state + 1;
                    return next >= targetCount
                            ? WaitForConditionResult.stopPolling(next)
                            : WaitForConditionResult.continuePolling(next);
                },
                0);
    }
}
