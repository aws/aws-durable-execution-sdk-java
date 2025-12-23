// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import java.time.Duration;

/**
 * Example demonstrating step execution with wait operations.
 *
 * <p>This handler processes a request through steps with delays: 1. Start processing 2. Wait 10 seconds 3. Continue
 * processing 4. Wait 5 seconds 5. Complete
 */
public class WaitExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected String handleRequest(GreetingRequest input, DurableContext context) {
        // Step 1: Start processing
        var started = context.step("start-processing", String.class, () -> "Started processing for " + input.getName());

        // Wait 10 seconds
        context.wait(Duration.ofSeconds(10));

        // Step 2: Continue processing
        var continued = context.step("continue-processing", String.class, () -> started + " - continued after 10s");

        // Wait 5 seconds
        context.wait(Duration.ofSeconds(5));

        // Step 3: Complete
        var result = context.step("complete-processing", String.class, () -> continued + " - completed after 5s more");

        return result;
    }
}
