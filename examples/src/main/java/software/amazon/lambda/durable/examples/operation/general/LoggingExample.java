// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.general;

import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.examples.types.GreetingRequest;

/**
 * Example demonstrating DurableLogger usage for structured logging with execution context.
 *
 * <p>The logger automatically includes execution metadata (durableExecutionArn, requestId, operationId, operationName)
 * in log entries via MDC. By default, logs are suppressed during replay to avoid duplicates.
 */
public class LoggingExample extends DurableHandler<GreetingRequest, String> {
    Logger logger = LoggerFactory.getLogger(LoggingExample.class);

    @Override
    public String handleRequest(GreetingRequest input) {
        var context = DurableContext.getCurrentContext();
        // Log at execution level (outside any step)
        context.getLogger(logger).info("Processing greeting for: {}", input.getName());

        // Step 1: Create greeting - logs inside step include operation context
        var greeting = step("create-greeting", String.class, () -> {
            StepContext.getCurrentContext().getLogger(logger).info("Creating greeting message");
            return "Hello, " + input.getName();
        });

        // Step 2: Transform
        var result = step("transform", String.class, () -> {
            StepContext.getCurrentContext().getLogger().info("Transforming greeting to uppercase");
            return greeting.toUpperCase() + "!";
        });

        context.getLogger().info("Completed processing, result: {}", result);
        return result;
    }
}
