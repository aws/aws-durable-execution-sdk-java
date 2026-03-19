// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/**
 * Example demonstrating the waitForCondition operation.
 *
 * <p>This example simulates waiting for an order to ship, by repeatedly calling a check function.
 */
public class WaitForConditionExample extends DurableHandler<String, String> {

    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public String handleRequest(String input, DurableContext context) {
        // Poll the shipment status until the order is shipped.
        // The check function simulates an order shipment status
        // which transitions from PENDING > PROCESSING > SHIPPED
        return context.waitForCondition(
                "wait-for-shipment",
                String.class,
                (status, stepCtx) -> {
                    // Simulate checking shipment status from an external service
                    var count = callCount.incrementAndGet();
                    if (count >= 3) {
                        // Order has shipped — stop polling
                        return WaitForConditionResult.stopPolling(input + ": SHIPPED");
                    }
                    // Order still processing — continue polling
                    return WaitForConditionResult.continuePolling(input + ": PROCESSING");
                },
                input + ": PENDING"); // Order pending - initial status
    }
}
