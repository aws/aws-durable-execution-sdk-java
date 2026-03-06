// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Example demonstrating non-blocking wait with waitAsync().
 *
 * <p>This handler starts a wait and a step concurrently, then collects both results. The wait acts as a minimum
 * duration guarantee — the step runs in parallel, and the handler only proceeds once both the step completes and the
 * wait elapses.
 *
 * <ol>
 *   <li>Start a 5-second async wait (non-blocking)
 *   <li>Start an async step concurrently
 *   <li>Collect both results — ensures at least 5 seconds have passed
 * </ol>
 */
public class WaitAsyncExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting waitAsync example for {}", input.getName());

        // Start a non-blocking wait — returns immediately
        DurableFuture<Void> waitFuture = context.waitAsync("min-delay", Duration.ofSeconds(5));

        // Run a step concurrently while the wait timer is ticking
        DurableFuture<String> stepFuture =
                context.stepAsync("process", String.class, stepCtx -> "Processed: " + input.getName());

        // Block until both complete — guarantees at least 5 seconds elapsed
        waitFuture.get();
        var result = stepFuture.get();

        context.getLogger().info("Both wait and step complete: {}", result);
        return result;
    }
}
