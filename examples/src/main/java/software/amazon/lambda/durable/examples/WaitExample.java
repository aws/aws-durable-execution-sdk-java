// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Example demonstrating step execution with wait operations.
 *
 * <p>This handler processes a request through steps with delays:
 *
 * <ol>
 *   <li>Start processing
 *   <li>Wait 10 seconds
 *   <li>Continue processing
 *   <li>Wait 5 seconds
 *   <li>Complete
 * </ol>
 */
public class WaitExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        // Step 1: Start processing
        var started =
                context.step("start-processing", String.class, stepCtx -> "Started processing for " + input.getName());

        // Wait 10 seconds
        context.wait(null, Duration.ofSeconds(10));

        // Step 2: Continue processing
        var continued = context.stepAsync("continue-processing", String.class, stepCtx -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return started + " - continued after 10s";
        });

        // Wait at most seconds
        var wait5seconds = context.waitAsync(null, Duration.ofSeconds(5));

        DurableFuture.anyOf(continued, wait5seconds);

        // Step 3: Complete
        var result =
                context.step("complete-processing", String.class, stepCtx -> continued + " - completed after 5s more");

        return result;
    }
}
