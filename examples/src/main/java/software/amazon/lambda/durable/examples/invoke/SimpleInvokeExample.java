// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.examples.types.GreetingRequest;

/**
 * Simple example demonstrating basic invoke execution with the Durable Execution SDK.
 *
 * <p>This handler invokes another Lambda function, such as simple-step-example.
 */
public class SimpleInvokeExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        var targetFunctionName =
                System.getenv().getOrDefault("FUNCTION_NAME_PREFIX", "") + "simple-step-example:$LATEST";

        // Invoke the `simple-step-example` function.
        var future = context.invokeAsync(
                "call-greeting1",
                targetFunctionName,
                input,
                String.class,
                InvokeConfig.builder().build());
        var result2 = context.invoke(
                "call-greeting2",
                targetFunctionName,
                input,
                String.class,
                InvokeConfig.builder().build());
        return future.get() + result2;
    }
}
