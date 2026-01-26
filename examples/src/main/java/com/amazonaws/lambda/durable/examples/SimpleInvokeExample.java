// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.InvokeConfig;

/**
 * Simple example demonstrating basic invoke execution with the Durable Execution SDK.
 *
 * <p>This handler invokes another durable lambda function simple-step-example</p>
 */
public class SimpleInvokeExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        // invoke `simple-step-example` function
        return context.invoke(
                "create-greeting",
                "simple-step-example:$LATEST",
                input,
                String.class,
                InvokeConfig.builder().build());
    }
}
