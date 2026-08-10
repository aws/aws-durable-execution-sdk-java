// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.wait;

import java.time.Duration;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.operation.DurableContextOperation;
import software.amazon.lambda.durable.operation.DurableStepOperation;
import software.amazon.lambda.durable.operation.DurableWaitOperation;

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
    public String handleRequest(GreetingRequest input) {
        // Step 1: Start processing
        var started = DurableStepOperation.step(
                "start-processing", String.class, () -> "Started processing for " + input.getName());

        // Wait 10 seconds
        DurableWaitOperation.wait(null, Duration.ofSeconds(10));

        // Step 2: Continue processing
        var continued = DurableStepOperation.stepAsync("continue-processing", String.class, () -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return started + " - continued after 10s";
        });

        // Wait at most seconds
        var wait5seconds = DurableContextOperation.runInChildContextAsync("wait-5-seconds", String.class, () -> {
            DurableWaitOperation.wait("wait-5-seconds", Duration.ofSeconds(5));

            return started + " - waited 5 seconds";
        });

        var step2 = DurableFuture.anyOf(continued, wait5seconds);

        // Step 3: Complete
        var result = DurableStepOperation.step(
                "complete-processing", String.class, () -> step2 + " - completed after 5s more");

        return result;
    }
}
