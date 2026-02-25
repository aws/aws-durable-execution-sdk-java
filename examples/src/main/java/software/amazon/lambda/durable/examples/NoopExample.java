// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/**
 * Simple example demonstrating a durable function doesn't have any durable operation
 *
 * <p>This handler processes a greeting request and returns a greeting message
 */
public class NoopExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        return "HELLO, " + input.getName() + "!";
    }
}
