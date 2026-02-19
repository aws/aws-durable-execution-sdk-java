// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.InvokeConfig;

/**
 * Simple example demonstrating basic invoke execution with the Durable Execution SDK.
 *
 * <p>This handler invokes another durable lambda function simple-step-example
 */
public class SimpleInvokeExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        // invoke `simple-step-example` function
        var future = context.invokeAsync(
                "call-greeting1",
                "simple-step-example" + input.getName() + ":$LATEST",
                input,
                String.class,
                InvokeConfig.builder().build());
        var result2 = context.invoke(
                "call-greeting2",
                "simple-step-example" + input.getName() + ":$LATEST",
                input,
                String.class,
                InvokeConfig.builder().build());
        return future.get() + result2;
    }
}
